package org.example.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpToolResult(
    val isError: Boolean,
    val content: String,
)

interface McpGateway {
    suspend fun start() = Unit
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult
    suspend fun schedulerSnapshot(): SchedulerSnapshot? = null
    suspend fun close()
}

/** Reusable stdio gateway, activated eagerly by web runtime. Failed protocol operations tear down the child process. */
class LocalMcpGateway(
    private val schedulerStateFile: Path = Path.of(DEFAULT_SCHEDULER_STATE_FILE_NAME),
) : McpGateway {
    private val mutex = Mutex()
    private var process: Process? = null
    private var client: Client? = null
    private var cachedTools: List<McpTool>? = null

    override suspend fun start() {
        protocolOperation { Unit }
    }

    override suspend fun listTools(): List<McpTool> = protocolOperation {
        cachedTools ?: withTimeout(MCP_TIMEOUT) {
            requireClient().listTools().tools.map { tool ->
                McpTool(
                    name = tool.name,
                    description = tool.description.orEmpty(),
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        put("properties", tool.inputSchema.properties ?: buildJsonObject {})
                        if (!tool.inputSchema.required.isNullOrEmpty()) {
                            put("required", buildJsonArray { tool.inputSchema.required.orEmpty().forEach(::add) })
                        }
                        if (!tool.inputSchema.defs.isNullOrEmpty()) put("${'$'}defs", requireNotNull(tool.inputSchema.defs))
                    },
                )
            }.also { cachedTools = it }
        }
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult = protocolOperation {
        callToolLocked(name, arguments)
    }

    override suspend fun schedulerSnapshot(): SchedulerSnapshot = protocolOperation {
        val result = callToolLocked(SCHEDULER_LIST_TOOL, buildJsonObject {})
        check(!result.isError) { "Scheduler snapshot is unavailable" }
        schedulerSnapshotFromJson(Json.parseToJsonElement(result.content))
    }

    private suspend fun callToolLocked(name: String, arguments: JsonObject): McpToolResult {
        val result = withTimeout(MCP_TIMEOUT) {
            requireClient().callTool(
                CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
            )
        }
        val content = result.structuredContent?.toString()
            ?: result.content.filterIsInstance<TextContent>().joinToString("\n", transform = TextContent::text)
        return McpToolResult(result.isError == true, content)
    }

    override suspend fun close() {
        mutex.withLock { closeLocked() }
    }

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
        val started = startServerProcess(schedulerStateFile)
        val connectedClient = Client(
            clientInfo = Implementation(
                name = "llm-workbench-mcp-client",
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

private val MCP_TIMEOUT = 10.seconds

internal fun startServerProcess(schedulerStateFile: Path = Path.of(DEFAULT_SCHEDULER_STATE_FILE_NAME)): Process {
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
        builder.environment()["LLM_SCHEDULER_STATE_FILE"] = schedulerStateFile.toAbsolutePath().toString()
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
