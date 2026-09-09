package org.example.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.llm.LlmMessage
import org.example.llm.LlmRole
import org.example.llm.TokenUsage
import org.example.tokens.*
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

const val DEFAULT_HISTORY_FILE_NAME = ".llm-history.json"
private const val HISTORY_FORMAT_VERSION = 2

typealias ConversationHistorySnapshot = Map<String, List<LlmMessage>>

data class ConversationPersistenceSnapshot(
    val messages: ConversationHistorySnapshot = emptyMap(),
    val turnMetrics: Map<String, List<TurnTokenMetrics>> = emptyMap(),
)

/** Persistence boundary for dialogue history. Callers never depend on files or JSON. */
interface ConversationHistoryStore {
    fun load(): ConversationHistorySnapshot
    fun save(history: ConversationHistorySnapshot)
    fun loadState(): ConversationPersistenceSnapshot = ConversationPersistenceSnapshot(load())
    fun saveState(state: ConversationPersistenceSnapshot) = save(state.messages)
    fun clear() = saveState(ConversationPersistenceSnapshot())
}

/** Default for tests and embedded uses which did not opt into persistent storage. */
object NoOpConversationHistoryStore : ConversationHistoryStore {
    override fun load(): ConversationHistorySnapshot = emptyMap()
    override fun save(history: ConversationHistorySnapshot) = Unit
    override fun loadState() = ConversationPersistenceSnapshot()
    override fun saveState(state: ConversationPersistenceSnapshot) = Unit
}

class HistoryPersistenceException(cause: Exception) :
    IOException("Не удалось сохранить историю диалога.", cause)

/** Versioned UTF-8 JSON storage with replace-on-success writes. */
class JsonConversationHistoryStore(
    private val historyFile: Path = Path.of(DEFAULT_HISTORY_FILE_NAME),
    private val replaceFile: (temporaryFile: Path, targetFile: Path) -> Unit = ::replaceAtomically,
) : ConversationHistoryStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    @Synchronized
    override fun load(): ConversationHistorySnapshot = loadState().messages

    @Synchronized
    override fun loadState(): ConversationPersistenceSnapshot {
        if (!Files.exists(historyFile)) return ConversationPersistenceSnapshot()

        val stored = json.decodeFromString<StoredHistory>(
            Files.readString(historyFile, StandardCharsets.UTF_8),
        )
        require(stored.version in 1..HISTORY_FORMAT_VERSION) {
            "Unsupported history format version"
        }
        require(stored.branches.map(StoredBranch::id).distinct().size == stored.branches.size) {
            "Duplicate history branch"
        }
        require(stored.branches.none { it.id.isBlank() }) { "Blank history branch id" }

        val messages = stored.branches.associate { branch ->
            branch.id to branch.messages.map { message ->
                LlmMessage(message.role.toLlmRole(), message.text)
            }
        }
        val turns = stored.branches.associate { branch -> branch.id to branch.turns.map(StoredTurn::toDomain) }
        return ConversationPersistenceSnapshot(messages, turns)
    }

    @Synchronized
    override fun save(history: ConversationHistorySnapshot) {
        saveState(ConversationPersistenceSnapshot(messages = history))
    }

    @Synchronized
    override fun saveState(state: ConversationPersistenceSnapshot) {
        val stored = StoredHistory(
            branches = state.messages.map { (id, messages) ->
                require(id.isNotBlank()) { "Blank history branch id" }
                StoredBranch(
                    id = id,
                    messages = messages.map { message ->
                        StoredMessage(StoredRole.from(message.role), message.content)
                    },
                    turns = state.turnMetrics[id].orEmpty().map(StoredTurn::fromDomain),
                )
            },
        )
        val bytes = json.encodeToString(stored).toByteArray(StandardCharsets.UTF_8)
        val absoluteFile = historyFile.toAbsolutePath()
        val parent = absoluteFile.parent

        try {
            Files.createDirectories(parent)
            val temporaryFile = Files.createTempFile(parent, ".llm-history-", ".tmp")
            try {
                Files.write(temporaryFile, bytes)
                replaceFile(temporaryFile, absoluteFile)
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        } catch (error: Exception) {
            if (error is HistoryPersistenceException) throw error
            throw HistoryPersistenceException(error)
        }
    }
}

private fun replaceAtomically(temporaryFile: Path, targetFile: Path) {
    try {
        Files.move(
            temporaryFile,
            targetFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
    }
}

@Serializable
private data class StoredHistory(
    val version: Int = HISTORY_FORMAT_VERSION,
    val branches: List<StoredBranch>,
)

@Serializable
private data class StoredBranch(
    val id: String,
    val messages: List<StoredMessage>,
    val turns: List<StoredTurn> = emptyList(),
)

@Serializable
private data class StoredEstimate(
    val tokens: Int,
    val exact: Boolean,
    val estimatorId: String,
    val warning: String? = null,
)

@Serializable
private data class StoredUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedPromptTokens: Int = 0,
    val cacheWritePromptTokens: Int = 0,
    val reasoningTokens: Int = 0,
)

@Serializable
private data class StoredBudget(
    val contextWindow: Int? = null,
    val requestedMaxOutputTokens: Int? = null,
    val reservedOutputTokens: Int? = null,
    val availableInputTokens: Int? = null,
    val estimatedContextUsagePercent: String? = null,
    val estimatedRemainingInputTokens: Int? = null,
)

@Serializable
private data class StoredTotals(
    val completedTurns: Int,
    val currentHistoryTokens: Int,
    val cumulativeApiInputTokens: Long? = null,
    val cumulativeOutputTokens: Long? = null,
    val cumulativeReasoningTokens: Long? = null,
    val cumulativeTotalTokens: Long? = null,
    val cumulativeCachedInputTokens: Long? = null,
    val cumulativeCacheWriteInputTokens: Long? = null,
    val cumulativeCostUsd: String? = null,
    val contextTruncations: Int,
    val scope: String,
)

@Serializable
private data class StoredTurn(
    val id: String,
    val turnNumber: Int,
    val model: String,
    val userMessage: String,
    val assistantMessage: String? = null,
    val estimatedCurrentMessageTokens: StoredEstimate,
    val estimatedHistoryTokens: StoredEstimate,
    val estimatedContextTokens: StoredEstimate,
    val actualUsage: StoredUsage? = null,
    val contextBudget: StoredBudget,
    val finishReason: String? = null,
    val turnCostUsd: String? = null,
    val cumulativeTotals: StoredTotals,
    val overflowPolicy: String,
    val excludedMessageCount: Int = 0,
    val excludedEstimatedTokens: Int = 0,
    val requiredTokens: Int,
    val exceededByTokens: Int = 0,
    val pricingProfileId: String? = null,
    val pricingEffectiveDate: String? = null,
    val pricingSourceUrl: String? = null,
    val contextProfileSimulated: Boolean = false,
    val createdAtEpochMillis: Long,
) {
    fun toDomain() = TurnTokenMetrics(
        id, turnNumber, model, userMessage, assistantMessage,
        estimatedCurrentMessageTokens.toDomain(), estimatedHistoryTokens.toDomain(), estimatedContextTokens.toDomain(),
        actualUsage?.toDomain(), contextBudget.toDomain(), finishReason, turnCostUsd?.toBigDecimal(),
        cumulativeTotals.toDomain(), ContextOverflowPolicy.valueOf(overflowPolicy), excludedMessageCount,
        excludedEstimatedTokens, requiredTokens, exceededByTokens, pricingProfileId,
        pricingEffectiveDate?.let(LocalDate::parse), pricingSourceUrl, contextProfileSimulated, createdAtEpochMillis,
    )

    companion object {
        fun fromDomain(turn: TurnTokenMetrics) = StoredTurn(
            turn.id, turn.turnNumber, turn.model, turn.userMessage, turn.assistantMessage,
            storedEstimate(turn.estimatedCurrentMessageTokens),
            storedEstimate(turn.estimatedHistoryTokens),
            storedEstimate(turn.estimatedContextTokens),
            turn.actualUsage?.let(::storedUsage), storedBudget(turn.contextBudget),
            turn.finishReason, turn.turnCostUsd?.toPlainString(), storedTotals(turn.cumulativeTotals),
            turn.overflowPolicy.name, turn.excludedMessageCount, turn.excludedEstimatedTokens, turn.requiredTokens,
            turn.exceededByTokens, turn.pricingProfileId, turn.pricingEffectiveDate?.toString(),
            turn.pricingSourceUrl, turn.contextProfileSimulated, turn.createdAtEpochMillis,
        )
    }
}

private fun StoredEstimate.toDomain() = TokenEstimate(tokens, exact, estimatorId, warning)
private fun StoredBudget.toDomain() = ContextBudget(contextWindow, requestedMaxOutputTokens, reservedOutputTokens,
    availableInputTokens, estimatedContextUsagePercent?.toBigDecimal(), estimatedRemainingInputTokens)
private fun StoredUsage.toDomain() = TokenUsage(promptTokens, completionTokens, totalTokens,
    cachedPromptTokens, cacheWritePromptTokens, reasoningTokens)
private fun StoredTotals.toDomain() = ConversationTokenTotals(completedTurns, currentHistoryTokens,
    cumulativeApiInputTokens, cumulativeOutputTokens, cumulativeReasoningTokens, cumulativeTotalTokens,
    cumulativeCachedInputTokens, cumulativeCacheWriteInputTokens, cumulativeCostUsd?.toBigDecimal(), contextTruncations, scope)

private fun storedEstimate(value: TokenEstimate) =
    StoredEstimate(value.tokens, value.exact, value.estimatorId, value.warning)
private fun storedUsage(value: TokenUsage) = StoredUsage(value.promptTokens,
    value.completionTokens, value.totalTokens, value.cachedPromptTokens, value.cacheWritePromptTokens, value.reasoningTokens)
private fun storedBudget(value: ContextBudget) = StoredBudget(value.contextWindow,
    value.requestedMaxOutputTokens, value.reservedOutputTokens, value.availableInputTokens,
    value.estimatedContextUsagePercent?.toPlainString(), value.estimatedRemainingInputTokens)
private fun storedTotals(value: ConversationTokenTotals) = StoredTotals(value.completedTurns,
    value.currentHistoryTokens, value.cumulativeApiInputTokens, value.cumulativeOutputTokens,
    value.cumulativeReasoningTokens, value.cumulativeTotalTokens, value.cumulativeCachedInputTokens,
    value.cumulativeCacheWriteInputTokens, value.cumulativeCostUsd?.toPlainString(), value.contextTruncations, value.scope)

@Serializable
private data class StoredMessage(
    val role: StoredRole,
    val text: String,
)

@Serializable
private enum class StoredRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT;

    fun toLlmRole(): LlmRole = when (this) {
        USER -> LlmRole.USER
        ASSISTANT -> LlmRole.ASSISTANT
    }

    companion object {
        fun from(role: LlmRole): StoredRole = when (role) {
            LlmRole.USER -> USER
            LlmRole.ASSISTANT -> ASSISTANT
        }
    }
}
