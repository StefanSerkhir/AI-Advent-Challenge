package org.example.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import java.nio.file.Path

internal const val MCP_DEMO_SERVER_NAME = "llm-workbench-mcp-demo"
internal const val MCP_DEMO_VERSION = "1.0.0"
internal const val TRACKER_GET_ISSUE_TOOL = "tracker_get_issue"
internal const val SCHEDULER_CREATE_TOOL = "scheduler_create"
internal const val SCHEDULER_LIST_TOOL = "scheduler_list"
internal const val SCHEDULER_CANCEL_TOOL = "scheduler_cancel"
internal const val SCHEDULER_SUMMARY_TOOL = "scheduler_get_summary"
internal val MCP_DEMO_TOOL_NAMES = setOf(
    "ping", "echo", TRACKER_GET_ISSUE_TOOL,
    SCHEDULER_CREATE_TOOL, SCHEDULER_LIST_TOOL, SCHEDULER_CANCEL_TOOL, SCHEDULER_SUMMARY_TOOL,
)

/**
 * Minimal local MCP server. Standard output is reserved exclusively for the
 * newline-delimited JSON-RPC messages used by the stdio transport.
 */
public fun main(): Unit = runBlocking {
    val stateFile = System.getenv("LLM_SCHEDULER_STATE_FILE")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: Path.of(DEFAULT_SCHEDULER_STATE_FILE_NAME)
    val scheduler = SchedulerService(
        JsonSchedulerStore(stateFile),
        LocalScheduledTaskExecutor(LocalDemoTrackerSource()),
    )
    scheduler.start()
    val server = createDemoServer(scheduler)
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
        scheduler.close()
    }
}

internal fun createDemoServer(scheduler: SchedulerService): Server = Server(
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

    addTool(
        name = SCHEDULER_CREATE_TOOL,
        description = "Creates a persisted local background schedule. Use requestId as an idempotency key. " +
            "scheduleType is once (runAt only) or fixed_interval (everySeconds and optional startAt). " +
            "taskType is reminder (reminderText only) or tracker_snapshot (issueId only).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringSchema("requestId", "Unique idempotency key for this creation request.", minLength = 1, maxLength = 200)
                stringSchema("title", "Human-readable schedule title.", minLength = 1, maxLength = 200)
                enumSchema("scheduleType", listOf("once", "fixed_interval"), "Schedule kind.")
                stringSchema("runAt", "Required only for once: exact ISO-8601 timestamp with UTC or offset.", format = "date-time")
                putJsonObject("everySeconds") {
                    put("type", "integer")
                    put("minimum", MIN_INTERVAL_SECONDS)
                    put("maximum", MAX_INTERVAL_SECONDS)
                    put("description", "Required only for fixed_interval.")
                }
                stringSchema("startAt", "Optional only for fixed_interval: ISO-8601 timestamp with UTC or offset.", format = "date-time")
                enumSchema("taskType", listOf("reminder", "tracker_snapshot"), "Background task kind.")
                stringSchema("reminderText", "Required only for reminder.", minLength = 1, maxLength = 4_000)
                stringSchema(
                    "issueId",
                    "Required only for tracker_snapshot, for example DEMO-101.",
                    minLength = 1,
                    maxLength = 64,
                    pattern = "^[A-Z][A-Z0-9_]*-[1-9][0-9]*$",
                )
            },
            required = listOf("requestId", "title", "scheduleType", "taskType"),
        ),
    ) { request ->
        safeToolCall {
            val args = request.arguments ?: buildJsonObject {}
            requireOnlyKeys(args, CREATE_ARGUMENTS)
            val scheduleType = when (args.requiredString("scheduleType")) {
                "once" -> ScheduleType.ONCE
                "fixed_interval" -> ScheduleType.FIXED_INTERVAL
                else -> throw IllegalArgumentException("scheduleType должен быть once или fixed_interval.")
            }
            val taskType = when (args.requiredString("taskType")) {
                "reminder" -> ScheduledTaskType.REMINDER
                "tracker_snapshot" -> ScheduledTaskType.TRACKER_SNAPSHOT
                else -> throw IllegalArgumentException("taskType должен быть reminder или tracker_snapshot.")
            }
            val schedule = scheduler.create(SchedulerCreateCommand(
                requestId = args.requiredString("requestId"),
                title = args.requiredString("title"),
                scheduleType = scheduleType,
                runAt = args.optionalString("runAt")?.let { parseInstant(it, "runAt") },
                everySeconds = args.optionalLong("everySeconds"),
                startAt = args.optionalString("startAt")?.let { parseInstant(it, "startAt") },
                taskType = taskType,
                reminderText = args.optionalString("reminderText"),
                issueId = args.optionalString("issueId"),
            ))
            val result = scheduler.summaries(schedule.id).toJson()
            structuredResult(result)
        }
    }

    addTool(
        name = SCHEDULER_LIST_TOOL,
        description = "Lists all persisted background schedules with states, last/next run times and execution counters.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) { request ->
        safeToolCall {
            val args = request.arguments ?: buildJsonObject {}
            requireOnlyKeys(args, emptySet())
            structuredResult(scheduler.summaries().toJson())
        }
    }

    addTool(
        name = SCHEDULER_CANCEL_TOOL,
        description = "Idempotently cancels one background schedule without deleting its accumulated results.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringSchema("scheduleId", "Identifier returned by scheduler_create or scheduler_list.", minLength = 1, maxLength = 200)
            },
            required = listOf("scheduleId"),
        ),
    ) { request ->
        safeToolCall {
            val args = request.arguments ?: buildJsonObject {}
            requireOnlyKeys(args, setOf("scheduleId"))
            val cancelled = scheduler.cancel(args.requiredString("scheduleId"))
            structuredResult(scheduler.summaries(cancelled.id).toJson())
        }
    }

    addTool(
        name = SCHEDULER_SUMMARY_TOOL,
        description = "Returns deterministic aggregated execution results for one schedule or all schedules. No LLM is called.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                stringSchema("scheduleId", "Optional schedule identifier; omit to summarize all schedules.", minLength = 1, maxLength = 200)
            },
        ),
    ) { request ->
        safeToolCall {
            val args = request.arguments ?: buildJsonObject {}
            requireOnlyKeys(args, setOf("scheduleId"))
            structuredResult(scheduler.summaries(args.optionalString("scheduleId")).toJson())
        }
    }
}

private fun demoIssue(includeComments: Boolean) = buildJsonObject {
    val issue = demoTrackerIssue()
    put("id", issue.id)
    put("title", issue.title)
    put("status", issue.status)
    put("assignee", issue.assignee)
    put("description", issue.description)
    put("nextAction", issue.nextAction)
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

private val CREATE_ARGUMENTS = setOf(
    "requestId", "title", "scheduleType", "runAt", "everySeconds", "startAt",
    "taskType", "reminderText", "issueId",
)

private fun JsonObjectBuilder.stringSchema(
    name: String,
    description: String,
    minLength: Int? = null,
    maxLength: Int? = null,
    format: String? = null,
    pattern: String? = null,
) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
        format?.let { put("format", it) }
        pattern?.let { put("pattern", it) }
    }
}

private fun JsonObjectBuilder.enumSchema(name: String, values: List<String>, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach(::add) })
        put("description", description)
    }
}

private suspend fun safeToolCall(block: suspend () -> CallToolResult): CallToolResult = try {
    block()
} catch (error: IllegalArgumentException) {
    toolError(error.message?.take(500) ?: "Некорректные аргументы инструмента.")
} catch (_: Exception) {
    toolError("Не удалось выполнить операцию планировщика.")
}

private fun structuredResult(result: JsonObject) = CallToolResult(
    content = listOf(TextContent(result.toString())),
    isError = false,
    structuredContent = result,
)

private fun requireOnlyKeys(arguments: JsonObject, allowed: Set<String>) {
    require(arguments.keys.all { it in allowed }) {
        "Переданы неизвестные поля: ${(arguments.keys - allowed).sorted().joinToString()}"
    }
}

private fun JsonObject.requiredString(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value?.isString == true) { "$name должен быть строкой." }
    return value.content
}

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    require(value is JsonPrimitive && value.isString) { "$name должен быть строкой." }
    return value.content
}

private fun JsonObject.optionalLong(name: String): Long? {
    val value = this[name] ?: return null
    require(value is JsonPrimitive && !value.isString && value.longOrNull != null) {
        "$name должен быть целым числом."
    }
    return value.long
}
