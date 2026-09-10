package org.example.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.llm.*
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

const val DEFAULT_CONTEXT_STATE_FILE_NAME = ".llm-context-history.json"
const val DEFAULT_RECENT_MESSAGES_LIMIT = 10
const val DEFAULT_SUMMARIZATION_BATCH_SIZE = 10

data class ContextCompressionConfig(
    val enabled: Boolean = true,
    val recentMessagesLimit: Int = DEFAULT_RECENT_MESSAGES_LIMIT,
    val summarizationBatchSize: Int = DEFAULT_SUMMARIZATION_BATCH_SIZE,
    val systemInstructions: String = "You are a helpful conversational assistant.",
) {
    init {
        require(recentMessagesLimit > 0) { "recentMessagesLimit must be positive" }
        require(summarizationBatchSize > 0) { "summarizationBatchSize must be positive" }
        require(systemInstructions.isNotBlank()) { "systemInstructions must not be blank" }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()) = ContextCompressionConfig(
            enabled = environment["CONTEXT_COMPRESSION_ENABLED"]?.toBooleanStrictOrNull() ?: true,
            recentMessagesLimit = environment["RECENT_MESSAGES_LIMIT"]?.toIntOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_RECENT_MESSAGES_LIMIT,
            summarizationBatchSize = environment["SUMMARIZATION_BATCH_SIZE"]?.toIntOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_SUMMARIZATION_BATCH_SIZE,
            systemInstructions = environment["AGENT_SYSTEM_INSTRUCTIONS"]
                ?.takeIf(String::isNotBlank) ?: "You are a helpful conversational assistant.",
        )
    }
}

data class SequencedMessage(
    val sequence: Long,
    val timestampEpochMillis: Long,
    val message: LlmMessage,
)

data class ContextUsageStats(
    val mainRequests: Int = 0,
    val summarizationRequests: Int = 0,
    val mainInputTokens: Long = 0,
    val mainOutputTokens: Long = 0,
    val mainTotalTokens: Long = 0,
    val summaryInputTokens: Long = 0,
    val summaryOutputTokens: Long = 0,
    val summaryTotalTokens: Long = 0,
) {
    val totalInputTokens: Long get() = mainInputTokens + summaryInputTokens
    val totalOutputTokens: Long get() = mainOutputTokens + summaryOutputTokens
    val totalTokens: Long get() = mainTotalTokens + summaryTotalTokens
    val averageMainInputTokens: Double get() =
        if (mainRequests == 0) 0.0 else mainInputTokens.toDouble() / mainRequests

    fun withMainUsage(usage: TokenUsage?) = if (usage == null) copy(mainRequests = mainRequests + 1) else copy(
        mainRequests = mainRequests + 1,
        mainInputTokens = mainInputTokens + usage.promptTokens,
        mainOutputTokens = mainOutputTokens + usage.completionTokens,
        mainTotalTokens = mainTotalTokens + usage.totalTokens,
    )

    fun withSummaryAttempt() = copy(summarizationRequests = summarizationRequests + 1)

    fun withSummaryUsage(usage: TokenUsage?) = if (usage == null) this else copy(
        summaryInputTokens = summaryInputTokens + usage.promptTokens,
        summaryOutputTokens = summaryOutputTokens + usage.completionTokens,
        summaryTotalTokens = summaryTotalTokens + usage.totalTokens,
    )
}

data class ContextSessionState(
    val sessionId: String,
    val summary: String = "",
    val recentMessages: List<SequencedMessage> = emptyList(),
    val pendingMessages: List<SequencedMessage> = emptyList(),
    val nextSequence: Long = 1,
    val stats: ContextUsageStats = ContextUsageStats(),
)

interface ContextStateStore {
    fun load(sessionId: String): ContextSessionState?
    fun save(state: ContextSessionState)
    fun clear(sessionId: String)
}

class InMemoryContextStateStore : ContextStateStore {
    private val states = linkedMapOf<String, ContextSessionState>()
    @Synchronized override fun load(sessionId: String) = states[sessionId]
    @Synchronized override fun save(state: ContextSessionState) { states[state.sessionId] = state }
    @Synchronized override fun clear(sessionId: String) { states.remove(sessionId) }
}

class JsonContextStateStore(
    private val file: Path = Path.of(DEFAULT_CONTEXT_STATE_FILE_NAME),
) : ContextStateStore {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    @Synchronized
    override fun load(sessionId: String): ContextSessionState? = readAll()[sessionId]?.toDomain()

    @Synchronized
    override fun save(state: ContextSessionState) {
        val states = readAll().toMutableMap()
        states[state.sessionId] = StoredContextSession.fromDomain(state)
        writeAll(states)
    }

    @Synchronized
    override fun clear(sessionId: String) {
        val states = readAll().toMutableMap()
        states.remove(sessionId)
        writeAll(states)
    }

    private fun readAll(): Map<String, StoredContextSession> {
        if (!Files.exists(file)) return emptyMap()
        val document = json.decodeFromString<StoredContextDocument>(Files.readString(file, StandardCharsets.UTF_8))
        require(document.version == 1) { "Unsupported context state format version" }
        return document.sessions.associateBy { it.sessionId }
    }

    private fun writeAll(states: Map<String, StoredContextSession>) {
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".llm-context-", ".tmp")
        try {
            Files.writeString(
                temporary,
                json.encodeToString(StoredContextDocument(sessions = states.values.toList())),
                StandardCharsets.UTF_8,
            )
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

fun interface HistorySummarizer {
    suspend fun summarize(previousSummary: String, messages: List<LlmMessage>): CompletionResult
}

class LlmHistorySummarizer(
    private val clientProvider: () -> LlmClient,
    private val options: CompletionOptions = CompletionOptions(temperature = 0.0),
) : HistorySummarizer {
    override suspend fun summarize(previousSummary: String, messages: List<LlmMessage>): CompletionResult {
        val rendered = messages.joinToString("\n") { "${it.role.apiValue}: ${it.content}" }
        return clientProvider().complete(
            listOf(
                LlmMessage(LlmRole.SYSTEM, SUMMARY_SYSTEM_PROMPT),
                LlmMessage(
                    LlmRole.USER,
                    "Previous summary:\n${previousSummary.ifBlank { "(empty)" }}\n\nNew messages:\n$rendered",
                ),
            ),
            options,
        )
    }
}

const val SUMMARY_SYSTEM_PROMPT = """Update the cumulative conversation summary using only the supplied material.
Keep it compact and structured under: Facts, Goals and requirements, Decisions, Constraints, Completed actions, Open questions, Errors and unresolved problems.
Preserve names, identifiers, numbers and changed decisions exactly. Do not invent facts. The returned text must replace the previous summary."""

class ContextManager(
    private val config: ContextCompressionConfig,
    private val store: ContextStateStore,
    private val summarizer: HistorySummarizer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun state(sessionId: String): ContextSessionState = store.load(validSessionId(sessionId))
        ?: ContextSessionState(sessionId)

    suspend fun addMessage(sessionId: String, message: LlmMessage) {
        require(message.role != LlmRole.SYSTEM) { "System messages are configured separately" }
        append(sessionId, listOf(message))
    }

    suspend fun addExchange(sessionId: String, user: LlmMessage, assistant: LlmMessage) {
        require(user.role == LlmRole.USER && assistant.role == LlmRole.ASSISTANT) {
            "An exchange must preserve user/assistant role order"
        }
        append(sessionId, listOf(user, assistant))
    }

    suspend fun contextFor(sessionId: String, currentUserMessage: LlmMessage): List<LlmMessage> {
        require(currentUserMessage.role == LlmRole.USER) { "Current message must have user role" }
        compactAvailableBatches(validSessionId(sessionId))
        val current = state(sessionId)
        return buildList {
            add(LlmMessage(LlmRole.SYSTEM, config.systemInstructions))
            if (config.enabled && current.summary.isNotBlank()) {
                add(LlmMessage(LlmRole.SYSTEM, "Cumulative summary of earlier conversation:\n${current.summary}"))
            }
            addAll(current.pendingMessages.map(SequencedMessage::message))
            addAll(current.recentMessages.map(SequencedMessage::message))
            add(currentUserMessage)
        }
    }

    fun recordMainUsage(sessionId: String, usage: TokenUsage?) {
        val current = state(validSessionId(sessionId))
        store.save(current.copy(stats = current.stats.withMainUsage(usage)))
    }

    fun clear(sessionId: String) = store.clear(validSessionId(sessionId))

    private suspend fun append(sessionId: String, messages: List<LlmMessage>) {
        val id = validSessionId(sessionId)
        var current = state(id)
        var sequence = current.nextSequence
        val appended = messages.map { message ->
            SequencedMessage(sequence++, clock(), message)
        }
        current = if (!config.enabled) {
            current.copy(recentMessages = current.recentMessages + appended, nextSequence = sequence)
        } else {
            val allRecent = current.recentMessages + appended
            val spillCount = (allRecent.size - config.recentMessagesLimit).coerceAtLeast(0)
            current.copy(
                pendingMessages = current.pendingMessages + allRecent.take(spillCount),
                recentMessages = allRecent.drop(spillCount),
                nextSequence = sequence,
            )
        }
        // Durable before the LLM call: a failed summary can never discard its input batch.
        store.save(current)
        compactAvailableBatches(id)
    }

    private suspend fun compactAvailableBatches(sessionId: String) {
        if (!config.enabled) return
        while (true) {
            val before = state(sessionId)
            if (before.pendingMessages.size < config.summarizationBatchSize) return
            val batch = before.pendingMessages.take(config.summarizationBatchSize)
            val attempted = before.copy(stats = before.stats.withSummaryAttempt())
            store.save(attempted)
            val result = summarizer.summarize(attempted.summary, batch.map(SequencedMessage::message))
            require(result.content.isNotBlank()) { "Summarizer returned an empty summary" }
            val after = attempted.copy(
                summary = result.content.trim(),
                pendingMessages = attempted.pendingMessages.drop(batch.size),
                stats = attempted.stats.withSummaryUsage(result.usage),
            )
            // The processed batch disappears only in the same state write that installs its summary.
            store.save(after)
        }
    }

    private fun validSessionId(sessionId: String): String = sessionId.also {
        require(it.isNotBlank()) { "sessionId must not be blank" }
    }
}

@Serializable private data class StoredContextDocument(val version: Int = 1, val sessions: List<StoredContextSession>)
@Serializable private data class StoredContextSession(
    val sessionId: String,
    val summary: String,
    val recentMessages: List<StoredSequencedMessage>,
    val pendingMessages: List<StoredSequencedMessage>,
    val nextSequence: Long,
    val stats: StoredContextUsage,
) {
    fun toDomain() = ContextSessionState(sessionId, summary, recentMessages.map { it.toDomain() },
        pendingMessages.map { it.toDomain() }, nextSequence, stats.toDomain())
    companion object {
        fun fromDomain(value: ContextSessionState) = StoredContextSession(value.sessionId, value.summary,
            value.recentMessages.map(StoredSequencedMessage::fromDomain),
            value.pendingMessages.map(StoredSequencedMessage::fromDomain), value.nextSequence,
            StoredContextUsage.fromDomain(value.stats))
    }
}
@Serializable private data class StoredSequencedMessage(
    val sequence: Long,
    val timestampEpochMillis: Long,
    val role: String,
    val content: String,
) {
    fun toDomain() = SequencedMessage(sequence, timestampEpochMillis, LlmMessage(LlmRole.valueOf(role), content))
    companion object { fun fromDomain(value: SequencedMessage) = StoredSequencedMessage(
        value.sequence, value.timestampEpochMillis, value.message.role.name, value.message.content) }
}
@Serializable private data class StoredContextUsage(
    val mainRequests: Int, val summarizationRequests: Int,
    val mainInputTokens: Long, val mainOutputTokens: Long, val mainTotalTokens: Long,
    val summaryInputTokens: Long, val summaryOutputTokens: Long, val summaryTotalTokens: Long,
) {
    fun toDomain() = ContextUsageStats(mainRequests, summarizationRequests, mainInputTokens, mainOutputTokens,
        mainTotalTokens, summaryInputTokens, summaryOutputTokens, summaryTotalTokens)
    companion object { fun fromDomain(value: ContextUsageStats) = StoredContextUsage(value.mainRequests,
        value.summarizationRequests, value.mainInputTokens, value.mainOutputTokens, value.mainTotalTokens,
        value.summaryInputTokens, value.summaryOutputTokens, value.summaryTotalTokens) }
}
