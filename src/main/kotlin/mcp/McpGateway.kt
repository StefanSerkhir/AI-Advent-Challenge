package org.example.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

internal const val OPERATIONS_SERVER_ID = "operations"
internal const val KNOWLEDGE_SERVER_ID = "knowledge"
internal const val WORKSPACE_SERVER_ID = "workspace"

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val serverId: String,
)

data class McpToolResult(
    val isError: Boolean,
    val content: String,
)

/** One independently launched MCP stdio server with a stable public provenance identifier. */
class McpServerRegistration(
    val serverId: String,
    val processFactory: () -> Process,
) {
    init {
        require(SAFE_SERVER_ID.matches(serverId)) {
            "MCP serverId must contain only lowercase ASCII letters, digits and hyphens."
        }
    }
}

class McpCatalogException(message: String) : IllegalStateException(message)

interface McpGateway {
    suspend fun start() = Unit
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult
    suspend fun schedulerSnapshot(): SchedulerSnapshot? = null
    suspend fun close()
}

/**
 * Registry-backed MCP orchestrator. Every registration owns an independent client, stdio transport,
 * child process, mutex and tools/list cache. The routing table is derived only from discovered catalogs.
 */
class LocalMcpGateway(
    schedulerStateFile: Path = Path.of(DEFAULT_SCHEDULER_STATE_FILE_NAME),
    outputDirectory: Path = Path.of(DEFAULT_MCP_OUTPUT_DIRECTORY),
    registrations: List<McpServerRegistration>? = null,
    private val schedulerServerId: String = OPERATIONS_SERVER_ID,
) : McpGateway {
    private val registrations = (registrations ?: defaultMcpServerRegistrations(schedulerStateFile, outputDirectory))
        .also { configured ->
            require(configured.isNotEmpty()) { "At least one MCP server registration is required." }
            val duplicateIds = configured.groupBy(McpServerRegistration::serverId).filterValues { it.size > 1 }.keys
            require(duplicateIds.isEmpty()) { "Duplicate MCP server registrations: ${duplicateIds.sorted().joinToString()}." }
        }
        .sortedBy(McpServerRegistration::serverId)
    private val sessions = this.registrations.associate { it.serverId to McpSession(it) }
    private val catalogMutex = Mutex()
    private var cachedCatalog: List<McpTool>? = null
    private var routes: Map<String, String> = emptyMap()

    override suspend fun start() {
        try {
            registrations.forEach { registration -> requireSession(registration.serverId).start() }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { close() }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { close() }
            throw error
        }
    }

    override suspend fun listTools(): List<McpTool> = try {
        catalogMutex.withLock { cachedCatalog ?: discoverCatalogLocked() }
    } catch (error: CancellationException) {
        withContext(NonCancellable) { close() }
        throw error
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        return try {
            val serverId = routeFor(name)
            requireSession(serverId).callTool(name, arguments)
        } catch (error: CancellationException) {
            withContext(NonCancellable) { close() }
            throw error
        } catch (error: Exception) {
            invalidateCatalog()
            throw error
        }
    }

    override suspend fun schedulerSnapshot(): SchedulerSnapshot {
        val catalogTool = listTools().firstOrNull { it.name == SCHEDULER_LIST_TOOL }
            ?: throw McpCatalogException("Scheduler tool is not registered.")
        check(catalogTool.serverId == schedulerServerId) { "Scheduler tool has an unexpected owner." }
        val result = callTool(SCHEDULER_LIST_TOOL, buildJsonObject {})
        check(!result.isError) { "Scheduler snapshot is unavailable" }
        return schedulerSnapshotFromJson(Json.parseToJsonElement(result.content))
    }

    override suspend fun close() {
        catalogMutex.withLock {
            cachedCatalog = null
            routes = emptyMap()
        }
        withContext(NonCancellable) {
            sessions.values.forEach { session -> runCatching { session.close() } }
        }
    }

    private suspend fun discoverCatalogLocked(): List<McpTool> {
        val discovered = registrations.flatMap { registration ->
            requireSession(registration.serverId).listTools().sortedBy(McpTool::name)
        }
        val collisions = discovered.groupBy(McpTool::name).filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            val details = collisions.toSortedMap().entries.joinToString("; ") { (name, tools) ->
                "'${safeToolName(name)}' on ${tools.map(McpTool::serverId).distinct().sorted().joinToString()}"
            }
            throw McpCatalogException("Conflicting MCP tool names: $details.")
        }
        routes = discovered.associate { it.name to it.serverId }
        return discovered.also { cachedCatalog = it }
    }

    private suspend fun routeFor(name: String): String {
        val current = catalogMutex.withLock { routes[name] }
        if (current != null) return current
        listTools()
        return catalogMutex.withLock { routes[name] }
            ?: throw McpCatalogException("MCP tool '${safeToolName(name)}' is not present in the discovered catalog.")
    }

    private suspend fun invalidateCatalog() = catalogMutex.withLock {
        cachedCatalog = null
        routes = emptyMap()
    }

    private fun requireSession(serverId: String): McpSession =
        checkNotNull(sessions[serverId]) { "MCP server '$serverId' is not registered." }
}

private class McpSession(private val registration: McpServerRegistration) {
    private val mutex = Mutex()
    private var process: Process? = null
    private var client: Client? = null
    private var cachedTools: List<McpTool>? = null

    suspend fun start() = protocolOperation { Unit }

    suspend fun listTools(): List<McpTool> = protocolOperation {
        cachedTools ?: withTimeout(MCP_TIMEOUT) {
            requireClient().listTools().tools.map { tool ->
                McpTool(
                    name = tool.name,
                    description = tool.description.orEmpty(),
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        tool.inputSchema.schema?.let { put("${'$'}schema", it) }
                        put("properties", tool.inputSchema.properties ?: buildJsonObject {})
                        if (!tool.inputSchema.required.isNullOrEmpty()) {
                            put("required", buildJsonArray { tool.inputSchema.required.orEmpty().forEach(::add) })
                        }
                        if (!tool.inputSchema.defs.isNullOrEmpty()) put("${'$'}defs", requireNotNull(tool.inputSchema.defs))
                    },
                    serverId = registration.serverId,
                )
            }.also { cachedTools = it }
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult = protocolOperation {
        val result = withTimeout(MCP_TIMEOUT) {
            requireClient().callTool(
                CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
            )
        }
        val content = result.structuredContent?.toString()
            ?: result.content.filterIsInstance<TextContent>().joinToString("\n", transform = TextContent::text)
        McpToolResult(result.isError == true, content)
    }

    suspend fun close() = mutex.withLock { closeLocked() }

    private suspend fun <T> protocolOperation(block: suspend () -> T): T = mutex.withLock {
        try {
            connectLocked()
            block()
        } catch (error: Exception) {
            withContext(NonCancellable) { closeLocked() }
            throw error
        }
    }

    private suspend fun connectLocked() {
        if (client != null && process?.isAlive == true) return
        closeLocked()
        val started = registration.processFactory()
        val connectedClient = Client(
            clientInfo = Implementation(
                name = "llm-workbench-mcp-client-${registration.serverId}",
                version = MCP_DEMO_VERSION,
            ),
        )
        process = started
        client = connectedClient
        val transport = StdioClientTransport(
            input = started.inputStream.asSource().buffered(),
            output = started.outputStream.asSink().buffered(),
            error = started.errorStream.asSource().buffered(),
            classifyStderr = { StdioClientTransport.StderrSeverity.WARNING },
        )
        withTimeout(MCP_TIMEOUT) { connectedClient.connect(transport) }
    }

    private fun requireClient(): Client = checkNotNull(client) { "MCP client is not connected" }

    private suspend fun closeLocked() {
        val closingClient = client
        val closingProcess = process
        client = null
        process = null
        cachedTools = null
        runCatching { closingClient?.close() }
        stopServerProcess(closingProcess)
    }
}

internal fun defaultMcpServerRegistrations(
    schedulerStateFile: Path,
    outputDirectory: Path,
): List<McpServerRegistration> = listOf(
    localMcpServerRegistration(OPERATIONS_SERVER_ID, schedulerStateFile, outputDirectory),
    localMcpServerRegistration(KNOWLEDGE_SERVER_ID, schedulerStateFile, outputDirectory),
    localMcpServerRegistration(WORKSPACE_SERVER_ID, schedulerStateFile, outputDirectory),
)

internal fun localMcpServerRegistration(
    serverId: String,
    schedulerStateFile: Path,
    outputDirectory: Path,
    onProcessStarted: (Process) -> Unit = {},
): McpServerRegistration = McpServerRegistration(serverId) {
    startServerProcess(serverId, schedulerStateFile, outputDirectory).also(onProcessStarted)
}

private val MCP_TIMEOUT = 10.seconds
private val SAFE_SERVER_ID = Regex("^[a-z][a-z0-9-]{0,63}$")

internal fun startServerProcess(
    serverId: String,
    schedulerStateFile: Path = Path.of(DEFAULT_SCHEDULER_STATE_FILE_NAME),
    outputDirectory: Path = Path.of(DEFAULT_MCP_OUTPUT_DIRECTORY),
): Process {
    require(SAFE_SERVER_ID.matches(serverId)) { "Invalid MCP server id." }
    val executable = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    ).toString()
    return ProcessBuilder(
        executable,
        "-cp",
        System.getProperty("java.class.path"),
        "org.example.mcp.McpDemoServerKt",
    ).also { builder ->
        builder.environment()["LLM_MCP_SERVER_ID"] = serverId
        if (serverId == OPERATIONS_SERVER_ID) {
            builder.environment()["LLM_SCHEDULER_STATE_FILE"] = schedulerStateFile.toAbsolutePath().toString()
        }
        if (serverId == WORKSPACE_SERVER_ID) {
            builder.environment()["LLM_MCP_OUTPUT_DIR"] = outputDirectory.toAbsolutePath().toString()
        }
    }.start()
}

internal fun stopServerProcess(process: Process?) {
    if (process == null) return
    runCatching { process.outputStream.close() }
    try {
        if (process.isAlive && !process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        if (process.isAlive && !process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    } catch (_: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
    }
}

private fun safeToolName(value: String): String =
    value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(128)
