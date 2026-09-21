package org.example.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val MCP_DEMO_SERVER_NAME = "llm-workbench-mcp-demo"
internal const val MCP_DEMO_VERSION = "1.0.0"
internal val MCP_DEMO_TOOL_NAMES = setOf("ping", "echo")

/**
 * Minimal local MCP server. Standard output is reserved exclusively for the
 * newline-delimited JSON-RPC messages used by the stdio transport.
 */
public fun main(): Unit = runBlocking {
    val server = createDemoServer()
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    val closed = CompletableDeferred<Unit>()

    try {
        val session = server.createSession(transport)
        session.onClose { closed.complete(Unit) }
        closed.await()
    } finally {
        server.close()
    }
}

private fun createDemoServer(): Server = Server(
    serverInfo = Implementation(
        name = MCP_DEMO_SERVER_NAME,
        version = MCP_DEMO_VERSION,
    ),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
        ),
    ),
) {
    addTool(
        name = "ping",
        description = "Checks that the local MCP server is reachable.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) {
        CallToolResult(content = listOf(TextContent("pong")))
    }

    addTool(
        name = "echo",
        description = "Returns the supplied text unchanged.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to return")
                }
            },
            required = listOf("text"),
        ),
    ) { request ->
        val text = request.arguments?.get("text")?.jsonPrimitive?.content.orEmpty()
        CallToolResult(content = listOf(TextContent(text)))
    }
}
