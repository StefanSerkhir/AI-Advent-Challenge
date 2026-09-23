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
import kotlinx.serialization.json.*

internal const val MCP_DEMO_SERVER_NAME = "llm-workbench-mcp-demo"
internal const val MCP_DEMO_VERSION = "1.0.0"
internal const val TRACKER_GET_ISSUE_TOOL = "tracker_get_issue"
internal val MCP_DEMO_TOOL_NAMES = setOf("ping", "echo", TRACKER_GET_ISSUE_TOOL)

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

    addTool(
        name = TRACKER_GET_ISSUE_TOOL,
        description = "Gets one issue from the local deterministic Tracker by its identifier.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("issueId") {
                    put("type", "string")
                    put("minLength", 1)
                    put("description", "Tracker issue identifier, for example DEMO-101. Must be a non-empty string.")
                }
                putJsonObject("includeComments") {
                    put("type", "boolean")
                    put("default", false)
                    put("description", "Whether to include issue comments. Defaults to false.")
                }
            },
            required = listOf("issueId"),
        ),
    ) { request ->
        val arguments = request.arguments
        val issueId = (arguments?.get("issueId") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
        val includeCommentsElement = arguments?.get("includeComments")
        val includeComments = (includeCommentsElement as? JsonPrimitive)?.booleanOrNull ?: false
        when {
            issueId.isNullOrEmpty() -> toolError("issueId must be a non-empty string.")
            !issueId.matches(Regex("[A-Z][A-Z0-9_]*-[1-9][0-9]*")) ->
                toolError("issueId has an invalid Tracker identifier format.")
            includeCommentsElement != null &&
                (includeCommentsElement as? JsonPrimitive)?.booleanOrNull == null ->
                toolError("includeComments must be a boolean when provided.")
            issueId != DEMO_ISSUE_ID -> toolError("Tracker issue '$issueId' was not found.")
            else -> {
                val result = demoIssue(includeComments)
                CallToolResult(
                    content = listOf(TextContent(result.toString())),
                    isError = false,
                    structuredContent = result,
                )
            }
        }
    }
}

private const val DEMO_ISSUE_ID = "DEMO-101"

private fun demoIssue(includeComments: Boolean) = buildJsonObject {
    put("id", DEMO_ISSUE_ID)
    put("title", "Подготовить первый MCP-инструмент")
    put("status", "In Progress")
    put("assignee", "Ирина Волкова")
    put("description", "Подключить локальный Tracker к Простому агенту через MCP stdio.")
    put("nextAction", "Завершить сквозные тесты и отправить изменение на проверку.")
    if (includeComments) {
        put("comments", buildJsonArray {
            add(buildJsonObject {
                put("author", "Ирина Волкова")
                put("text", "MCP-сервер и схема инструмента готовы к интеграционной проверке.")
            })
            add(buildJsonObject {
                put("author", "Алексей Смирнов")
                put("text", "Нужно подтвердить отображение диагностического следа в web UI.")
            })
        })
    }
}

private fun toolError(message: String) = CallToolResult(
    content = listOf(TextContent(message)),
    isError = true,
)
