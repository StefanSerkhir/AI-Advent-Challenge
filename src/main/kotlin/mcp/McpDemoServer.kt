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
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal const val MCP_DEMO_SERVER_NAME = "llm-workbench-mcp-demo"
internal const val MCP_DEMO_VERSION = "1.1.0"
internal const val DEFAULT_MCP_OUTPUT_DIRECTORY = ".llm-mcp-output"
internal const val TRACKER_GET_ISSUE_TOOL = "tracker_get_issue"
internal const val SEARCH_TOOL = "search"
internal const val SUMMARIZE_TOOL = "summarize"
internal const val SAVE_TO_FILE_TOOL = "save_to_file"
internal const val SCHEDULER_CREATE_TOOL = "scheduler_create"
internal const val SCHEDULER_LIST_TOOL = "scheduler_list"
internal const val SCHEDULER_CANCEL_TOOL = "scheduler_cancel"
internal const val SCHEDULER_SUMMARY_TOOL = "scheduler_get_summary"
internal val MCP_DEMO_TOOL_NAMES = setOf(
    "ping", "echo", TRACKER_GET_ISSUE_TOOL,
    SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TO_FILE_TOOL,
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
    val outputDirectory = System.getenv("LLM_MCP_OUTPUT_DIR")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: Path.of(DEFAULT_MCP_OUTPUT_DIRECTORY)
    val scheduler = SchedulerService(
        JsonSchedulerStore(stateFile),
        LocalScheduledTaskExecutor(LocalDemoTrackerSource()),
    )
    scheduler.start()
    val server = createDemoServer(scheduler, outputDirectory)
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

internal fun createDemoServer(
    scheduler: SchedulerService,
    outputDirectory: Path = Path.of(DEFAULT_MCP_OUTPUT_DIRECTORY),
): Server = Server(
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
        name = SEARCH_TOOL,
        description = "First pipeline step. Search the deterministic local knowledge catalog, then pass matches unchanged to summarize.",
        inputSchema = ToolSchema(
            schema = JSON_SCHEMA_DIALECT,
            properties = buildJsonObject {
                stringSchema(
                    "query",
                    "Required local search query. Unknown fields are rejected.",
                    minLength = 1,
                    maxLength = MAX_SEARCH_QUERY_LENGTH,
                )
            },
            required = listOf("query"),
        ),
    ) { request ->
        safeToolCall {
            val args = request.arguments ?: buildJsonObject {}
            requireOnlyKeys(args, setOf("query"))
            val query = args.requiredUnmodifiedString("query", MAX_SEARCH_QUERY_LENGTH)
            structuredResult(searchLocalKnowledge(query))
        }
    }

    addTool(
        name = SUMMARIZE_TOOL,
        description = "Second pipeline step. Summarize matches returned by search and preserve their IDs for save_to_file.",
        inputSchema = ToolSchema(
            schema = JSON_SCHEMA_DIALECT,
            properties = buildJsonObject {
                putJsonObject("matches") {
                    put("type", "array")
                    put("minItems", 0)
                    put("maxItems", MAX_SEARCH_MATCHES)
                    put("description", "The matches array returned by search, unchanged.")
                    put("items", matchSchema())
                }
                putJsonObject("maxSentences") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", MAX_SUMMARY_SENTENCES)
                    put("default", DEFAULT_SUMMARY_SENTENCES)
                    put("description", "Maximum number of source sentences to include.")
                }
            },
            required = listOf("matches"),
        ),
    ) { request ->
        safeToolCall {
            val args = request.arguments ?: buildJsonObject {}
            requireOnlyKeys(args, setOf("matches", "maxSentences"))
            val matches = args.requiredMatches()
            val maxSentences = args.optionalInt("maxSentences") ?: DEFAULT_SUMMARY_SENTENCES
            require(maxSentences in 1..MAX_SUMMARY_SENTENCES) {
                "maxSentences должен быть целым числом от 1 до $MAX_SUMMARY_SENTENCES."
            }
            structuredResult(summarizeMatches(matches, maxSentences))
        }
    }

    addTool(
        name = SAVE_TO_FILE_TOOL,
        description = "Third pipeline step. Save the summary from summarize as UTF-8 under the configured output directory only.",
        inputSchema = ToolSchema(
            schema = JSON_SCHEMA_DIALECT,
            properties = buildJsonObject {
                stringSchema(
                    "fileName",
                    "Safe relative file name without directories, for example pipeline-summary.md.",
                    minLength = 1,
                    maxLength = MAX_OUTPUT_FILE_NAME_LENGTH,
                    pattern = SAFE_FILE_NAME_REGEX.pattern,
                )
                stringSchema(
                    "content",
                    "Exact summary returned by summarize.",
                    minLength = 1,
                    maxLength = MAX_SAVED_CONTENT_LENGTH,
                )
                putJsonObject("sourceIds") {
                    put("type", "array")
                    put("maxItems", MAX_SEARCH_MATCHES)
                    put("uniqueItems", true)
                    put("description", "Optional sourceIds returned by summarize.")
                    putJsonObject("items") {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", MAX_SOURCE_ID_LENGTH)
                    }
                }
            },
            required = listOf("fileName", "content"),
        ),
    ) { request ->
        safeToolCall {
            val args = request.arguments ?: buildJsonObject {}
            requireOnlyKeys(args, setOf("fileName", "content", "sourceIds"))
            val fileName = args.requiredString("fileName")
            val content = args.requiredBoundedText("content", MAX_SAVED_CONTENT_LENGTH)
            val sourceIds = args.optionalSourceIds()
            structuredResult(saveSummary(outputDirectory, fileName, content, sourceIds))
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

private data class LocalKnowledgeEntry(val id: String, val title: String, val content: String)

private val LOCAL_KNOWLEDGE = listOf(
    LocalKnowledgeEntry(
        id = "mcp-composition-001",
        title = "Композиция MCP-инструментов",
        content = "Pipeline передаёт структурированный результат каждого MCP-инструмента следующему шагу без скрытого состояния.",
    ),
    LocalKnowledgeEntry(
        id = "mcp-tool-loop-002",
        title = "Ограниченный tool-calling loop",
        content = "Модель выбирает search, summarize и save_to_file последовательно, а финальный ответ формирует после трёх вызовов.",
    ),
    LocalKnowledgeEntry(
        id = "mcp-safety-003",
        title = "Локальная граница безопасности",
        content = "Инструменты не используют внешнюю сеть или LLM, а save_to_file записывает данные только в настроенный output-каталог.",
    ),
)

private data class SearchMatch(val id: String, val title: String, val content: String)

private fun searchLocalKnowledge(query: String): JsonObject {
    val terms = WORD_REGEX.findAll(query.lowercase()).map { it.value }.filter { it !in SEARCH_STOP_WORDS }.toSet()
    val matches = LOCAL_KNOWLEDGE.mapNotNull { entry ->
        val searchable = "${entry.id} ${entry.title} ${entry.content}".lowercase()
        val score = terms.count(searchable::contains)
        if (score == 0) null else score to entry
    }.sortedWith(compareByDescending<Pair<Int, LocalKnowledgeEntry>> { it.first }.thenBy { it.second.id })
        .take(MAX_SEARCH_MATCHES)
        .map { (_, entry) -> SearchMatch(entry.id, entry.title, entry.content) }
    return buildJsonObject {
        put("query", query)
        put("matchCount", matches.size)
        putJsonArray("matches") {
            matches.forEach { match ->
                add(buildJsonObject {
                    put("id", match.id)
                    put("title", match.title)
                    put("content", match.content)
                })
            }
        }
    }
}

private fun JsonObject.requiredMatches(): List<SearchMatch> {
    val array = this["matches"] as? JsonArray
        ?: throw IllegalArgumentException("matches должен быть массивом.")
    require(array.size <= MAX_SEARCH_MATCHES) { "matches содержит слишком много записей." }
    return array.mapIndexed { index, element ->
        val value = element as? JsonObject
            ?: throw IllegalArgumentException("matches[$index] должен быть объектом.")
        requireOnlyKeys(value, setOf("id", "title", "content"))
        SearchMatch(
            id = value.requiredUnmodifiedString("id", MAX_SOURCE_ID_LENGTH),
            title = value.requiredUnmodifiedString("title", MAX_MATCH_TITLE_LENGTH),
            content = value.requiredBoundedText("content", MAX_MATCH_CONTENT_LENGTH),
        )
    }
}

private fun summarizeMatches(matches: List<SearchMatch>, maxSentences: Int): JsonObject {
    val processed = matches.take(maxSentences)
    val summary = if (processed.isEmpty()) {
        "Совпадений в локальном каталоге не найдено."
    } else {
        processed.joinToString(" ") { match -> "${match.title}: ${match.content}" }
    }
    return buildJsonObject {
        put("summary", summary)
        putJsonArray("sourceIds") { processed.forEach { add(it.id) } }
        put("processedCount", processed.size)
    }
}

private fun JsonObject.optionalSourceIds(): List<String> {
    val element = this["sourceIds"] ?: return emptyList()
    val array = element as? JsonArray ?: throw IllegalArgumentException("sourceIds должен быть массивом строк.")
    require(array.size <= MAX_SEARCH_MATCHES) { "sourceIds содержит слишком много значений." }
    val values = array.mapIndexed { index, item ->
        val primitive = item as? JsonPrimitive
        require(primitive?.isString == true) { "sourceIds[$index] должен быть строкой." }
        primitive.content.also {
            require(it.isNotBlank() && it.length <= MAX_SOURCE_ID_LENGTH && it.none(Char::isISOControl)) {
                "sourceIds[$index] имеет недопустимую длину или символы."
            }
        }
    }
    require(values.distinct().size == values.size) { "sourceIds не должен содержать дубликаты." }
    return values
}

private fun saveSummary(outputDirectory: Path, fileName: String, content: String, sourceIds: List<String>): JsonObject {
    require(fileName.length <= MAX_OUTPUT_FILE_NAME_LENGTH && SAFE_FILE_NAME_REGEX.matches(fileName)) {
        "fileName должен быть безопасным именем файла без пути."
    }
    require(!Path.of(fileName).isAbsolute && fileName != "." && fileName != ".." &&
        '/' !in fileName && '\\' !in fileName && fileName.none(Char::isISOControl)) {
        "fileName должен быть относительным именем файла без каталогов."
    }
    if (Files.exists(outputDirectory, NOFOLLOW_LINKS)) {
        require(!Files.isSymbolicLink(outputDirectory)) { "Настроенный output-каталог не может быть символьной ссылкой." }
        require(Files.isDirectory(outputDirectory, NOFOLLOW_LINKS)) { "Настроенный output-путь не является каталогом." }
    } else {
        Files.createDirectories(outputDirectory)
    }
    val root = outputDirectory.toAbsolutePath().normalize()
    val target = root.resolve(fileName).normalize()
    require(target.parent == root) { "fileName выходит за пределы output-каталога." }
    require(!Files.isSymbolicLink(target)) { "Запись через существующую символьную ссылку запрещена." }
    val bytes = content.toByteArray(StandardCharsets.UTF_8)
    val temporary = Files.createTempFile(root, ".mcp-output-", ".tmp")
    try {
        Files.write(temporary, bytes)
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
    return buildJsonObject {
        put("fileName", fileName)
        put("bytesWritten", bytes.size)
        putJsonArray("sourceIds") { sourceIds.forEach(::add) }
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
    toolError("Не удалось выполнить локальный MCP-инструмент.")
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

private fun JsonObject.requiredUnmodifiedString(name: String, maxLength: Int): String {
    val value = requiredString(name)
    require(value.isNotBlank()) { "$name не должен быть пустым." }
    require(value.length <= maxLength) { "$name не должен быть длиннее $maxLength символов." }
    require(value.none(Char::isISOControl)) { "$name не должен содержать управляющие символы." }
    return value
}

private fun JsonObject.requiredBoundedText(name: String, maxLength: Int): String {
    val value = requiredString(name)
    require(value.isNotBlank()) { "$name не должен быть пустым." }
    require(value.length <= maxLength) { "$name не должен быть длиннее $maxLength символов." }
    require(value.all { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }) {
        "$name не должен содержать недопустимые управляющие символы."
    }
    return value
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

private fun JsonObject.optionalInt(name: String): Int? {
    val value = this[name] ?: return null
    require(value is JsonPrimitive && !value.isString && value.intOrNull != null) {
        "$name должен быть целым числом."
    }
    return value.int
}

private fun matchSchema() = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", buildJsonArray { listOf("id", "title", "content").forEach(::add) })
    putJsonObject("properties") {
        putJsonObject("id") {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", MAX_SOURCE_ID_LENGTH)
        }
        putJsonObject("title") {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", MAX_MATCH_TITLE_LENGTH)
        }
        putJsonObject("content") {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", MAX_MATCH_CONTENT_LENGTH)
        }
    }
}

private const val JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"
private const val MAX_SEARCH_QUERY_LENGTH = 200
private const val MAX_SEARCH_MATCHES = 10
private const val DEFAULT_SUMMARY_SENTENCES = 3
private const val MAX_SUMMARY_SENTENCES = 5
private const val MAX_SOURCE_ID_LENGTH = 64
private const val MAX_MATCH_TITLE_LENGTH = 200
private const val MAX_MATCH_CONTENT_LENGTH = 2_000
private const val MAX_OUTPUT_FILE_NAME_LENGTH = 128
private const val MAX_SAVED_CONTENT_LENGTH = 20_000
private val SAFE_FILE_NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val WORD_REGEX = Regex("[\\p{L}\\p{N}]+")
private val SEARCH_STOP_WORDS = setOf("о", "и", "в", "на", "по", "для", "the", "a", "of", "to", "about")
