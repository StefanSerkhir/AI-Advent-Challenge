package org.example.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val schemaJson = Json {
    prettyPrint = true
    explicitNulls = false
}

/**
 * Starts the demo MCP server as a child process, performs the MCP initialize
 * handshake and discovers tools with the protocol's tools/list method.
 */
public fun main() {
    val exitCode = runBlocking { runMcpDemoClient() }
    if (exitCode != 0) exitProcess(exitCode)
}

private suspend fun runMcpDemoClient(): Int {
    var stage = "starting the local MCP server"
    var process: Process? = null
    val client = Client(
        clientInfo = Implementation(
            name = "llm-workbench-mcp-demo-client",
            version = MCP_DEMO_VERSION,
        ),
    )

    return try {
        process = startServerProcess()
        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            error = process.errorStream.asSource().buffered(),
            classifyStderr = { StdioClientTransport.StderrSeverity.WARNING },
        )

        stage = "establishing the MCP connection"
        withTimeout(10.seconds) {
            client.connect(transport)
        }
        val server = checkNotNull(client.serverVersion) {
            "The MCP initialize response did not identify the server"
        }
        println("MCP connection established: ${server.name} ${server.version}")

        stage = "requesting tools/list"
        val tools = withTimeout(10.seconds) {
            client.listTools().tools.sortedBy { it.name }
        }
        check(tools.isNotEmpty()) { "The MCP server returned an empty tool list" }

        val returnedNames = tools.mapTo(mutableSetOf()) { it.name }
        check(returnedNames.containsAll(MCP_DEMO_TOOL_NAMES)) {
            "The MCP server did not return expected tools: ${MCP_DEMO_TOOL_NAMES - returnedNames}"
        }

        println("tools/list returned ${tools.size} tool(s):")
        tools.forEach { tool ->
            println("- name: ${tool.name}")
            println("  description: ${tool.description ?: "(not provided)"}")
            println("  inputSchema:")
            schemaJson.encodeToString(tool.inputSchema).lineSequence().forEach { line ->
                println("    $line")
            }
        }
        println("MCP tool discovery verified successfully.")
        0
    } catch (error: Exception) {
        System.err.println("MCP demo failed while $stage: ${error.message ?: error::class.simpleName}")
        1
    } finally {
        withContext(NonCancellable) {
            runCatching { client.close() }
                .onFailure { System.err.println("Failed to close MCP client cleanly: ${it.message}") }
            stopServerProcess(process)
        }
    }
}

private fun startServerProcess(): Process {
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
    ).start()
}

private fun stopServerProcess(process: Process?) {
    if (process == null) return

    runCatching { process.outputStream.close() }
    if (process.isAlive && !process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroy()
    }
    if (process.isAlive && !process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
    }
}
