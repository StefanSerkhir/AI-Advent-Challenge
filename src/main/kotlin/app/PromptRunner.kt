package org.example.app

import org.example.agent.*
import org.example.llm.CompletionOptions
import org.example.llm.CompletionResult
import org.example.llm.LlmClient
import org.example.llm.LlmMessage
import org.example.tokens.*

data class LabeledResponse(
    val variant: ResponseVariant,
    val completion: CompletionResult,
    val heading: String = variant.heading,
    val tokenMetrics: TurnTokenMetrics? = null,
    val contextStrategy: ContextStrategy? = null,
    val branchId: String? = null,
    val branchName: String? = null,
) {
    val content: String
        get() = completion.content
}

data class PromptProgress(
    val current: Int,
    val total: Int,
    val label: String,
)

class PromptRunner(
    private val onProgress: (PromptProgress) -> Unit = {},
    private val onDelta: (ExperimentOutputDelta) -> Unit = {},
    private val onResponse: (LabeledResponse) -> Unit = {},
    private val historyStore: ConversationHistoryStore = NoOpConversationHistoryStore,
    private val contextStateStore: ContextStateStore = InMemoryContextStateStore(),
    private val clientProvider: () -> LlmClient,
) {
    val historyLoadWarning: String?
    private val agents: Map<ResponseVariant, LlmAgent>
    private val contextManager = ContextManager(contextStateStore, LlmFactExtractor(clientProvider))

    init {
        val (restoredState, warning) = try {
            historyStore.loadState() to null
        } catch (_: Exception) {
            ConversationPersistenceSnapshot() to HISTORY_LOAD_WARNING
        }
        historyLoadWarning = warning
        agents = ResponseVariant.entries.associateWith { variant ->
            // Version 1/2 unrestricted history is deliberately not promoted to any new strategy.
            val restoredMessages = if (variant == ResponseVariant.UNRESTRICTED) emptyList() else restoredState.messages[variant.historyId].orEmpty()
            val restoredMetrics = restoredState.turnMetrics[variant.historyId].orEmpty().ifEmpty {
                legacyTurnMetrics(variant, restoredMessages)
            }
            LlmAgent(
                clientProvider = clientProvider,
                initialHistory = restoredMessages,
                initialTurnMetrics = restoredMetrics,
                persistHistory = { completedHistory -> persist(variant, completedHistory) },
                persistConversation = { completedHistory, turns -> persist(variant, completedHistory, turns) },
                contextManager = contextManager.takeIf { variant == ResponseVariant.UNRESTRICTED },
                contextSessionId = variant.historyId,
            )
        }
    }

    suspend fun complete(
        prompt: String,
        settings: AppSettings,
    ): List<LabeledResponse> {
        val variants = settings.responseMode.variants()
        return variants.mapIndexed { index, variant ->
            val sticky = settings.responseMode == ResponseMode.UNRESTRICTED && settings.historyEnabled &&
                settings.contextStrategy == ContextStrategy.STICKY_FACTS
            onProgress(
                PromptProgress(
                    current = if (sticky) 1 else index + 1,
                    total = if (sticky) 2 else variants.size,
                    label = when {
                        sticky -> "Обновление facts"
                        settings.responseMode == ResponseMode.UNRESTRICTED -> "Агент формирует ответ"
                        variant == ResponseVariant.UNRESTRICTED -> "Ответ без ограничений"
                        variant == ResponseVariant.CONTROLLED -> "Ответ с ограничениями"
                        else -> error("Неизвестный вариант ответа")
                    },
                ),
            )
            completeVariant(variant, prompt, settings).also(onResponse)
        }
    }

    /** Call from the owning worker, or while no request is running. */
    fun historySnapshot(): Map<ResponseVariant, List<LlmMessage>> = agents.mapValues { it.value.historySnapshot() }

    fun clearHistory() {
        val previousContext = contextManager.state(ResponseVariant.UNRESTRICTED.historyId)
        contextManager.clear(ResponseVariant.UNRESTRICTED.historyId)
        try {
            historyStore.clear()
        } catch (error: Exception) {
            runCatching { contextManager.restore(previousContext) }
            throw error
        }
        agents.values.forEach(LlmAgent::clearLocalHistory)
    }

    fun historyTurnCounts(): Map<ResponseVariant, Int> = agents.mapValues { it.value.completedTurnCount() }

    fun tokenMetricsSnapshot(): Map<ResponseVariant, List<TurnTokenMetrics>> =
        agents.mapValues { it.value.tokenMetricsSnapshot() }

    fun tokenTotalsSnapshot(): Map<ResponseVariant, ConversationTokenTotals> =
        agents.mapValues { it.value.conversationTotals() }

    fun contextDiagnostics(settings: AppSettings): ContextDiagnostics = contextManager.diagnostics(
        ResponseVariant.UNRESTRICTED.historyId,
        ContextConfig(settings.contextStrategy, settings.recentMessagesLimit),
    )

    fun createCheckpoint(settings: AppSettings): ContextDiagnostics {
        val diagnostics = contextManager.createCheckpoint(ResponseVariant.UNRESTRICTED.historyId)
        agents.getValue(ResponseVariant.UNRESTRICTED).replaceHistory(contextManager.activeMessages(
            ResponseVariant.UNRESTRICTED.historyId, ContextStrategy.BRANCHING))
        return diagnostics.copy(recentMessagesLimit = settings.recentMessagesLimit)
    }

    fun switchBranch(branchId: String, settings: AppSettings): ContextDiagnostics {
        val diagnostics = contextManager.switchBranch(ResponseVariant.UNRESTRICTED.historyId, branchId)
        agents.getValue(ResponseVariant.UNRESTRICTED).replaceHistory(contextManager.activeMessages(
            ResponseVariant.UNRESTRICTED.historyId, ContextStrategy.BRANCHING))
        return diagnostics.copy(recentMessagesLimit = settings.recentMessagesLimit)
    }

    private fun persist(
        changedVariant: ResponseVariant,
        completedHistory: List<LlmMessage>,
        completedTurns: List<TurnTokenMetrics> = agents.getValue(changedVariant).tokenMetricsSnapshot(),
    ) {
        val snapshot: ConversationHistorySnapshot = ResponseVariant.entries.associate { variant ->
            variant.historyId to if (variant == changedVariant) {
                completedHistory
            } else {
                agents.getValue(variant).historySnapshot()
            }
        }
        val metrics = ResponseVariant.entries.associate { variant ->
            variant.historyId to if (variant == changedVariant) completedTurns else agents.getValue(variant).tokenMetricsSnapshot()
        }
        historyStore.saveState(ConversationPersistenceSnapshot(snapshot, metrics))
    }

    private suspend fun completeVariant(
        variant: ResponseVariant,
        prompt: String,
        settings: AppSettings,
    ): LabeledResponse {
        val requestPrompt = when (variant) {
            ResponseVariant.UNRESTRICTED -> prompt
            ResponseVariant.CONTROLLED -> withResponseConstraints(prompt, settings)
        }
        val options = when (variant) {
            ResponseVariant.UNRESTRICTED -> CompletionOptions(
                maxTokens = settings.maxTokens.takeIf { settings.responseMode == ResponseMode.UNRESTRICTED },
            )
            ResponseVariant.CONTROLLED -> CompletionOptions(
                maxTokens = settings.maxTokens,
                stopSequences = settings.stopSequence?.let(::listOf).orEmpty(),
            )
        }
        val heading = if (settings.responseMode == ResponseMode.UNRESTRICTED) "ОТВЕТ АГЕНТА" else variant.heading
        val sticky = settings.responseMode == ResponseMode.UNRESTRICTED && settings.contextStrategy == ContextStrategy.STICKY_FACTS
        var preparedMetrics: TurnTokenMetrics? = null
        val response = agents.getValue(variant).respond(
            AgentRequest(
                prompt = requestPrompt,
                options = options,
                historyEnabled = settings.historyEnabled,
                model = settings.model,
                overflowPolicy = settings.contextOverflowPolicy,
                contextStrategy = settings.contextStrategy.takeIf { settings.responseMode == ResponseMode.UNRESTRICTED },
                recentMessagesLimit = settings.recentMessagesLimit,
                onContextPrepared = {
                    if (sticky) onProgress(PromptProgress(2, 2, "Агент формирует ответ"))
                },
            ),
            onDelta = { content -> onDelta(ExperimentOutputDelta(variant.name, heading, content, tokenMetrics = preparedMetrics)) },
            onMetrics = { metrics ->
                preparedMetrics = metrics
                if (settings.responseMode == ResponseMode.UNRESTRICTED && metrics.actualUsage == null) {
                    onDelta(ExperimentOutputDelta(variant.name, heading, "", tokenMetrics = metrics))
                }
            },
        )

        return LabeledResponse(
            variant = variant,
            completion = response.completion,
            heading = heading,
            tokenMetrics = response.tokenMetrics,
            contextStrategy = response.contextStrategy,
            branchId = response.branchId,
            branchName = response.branchName,
        )
    }

    private fun ResponseMode.variants(): List<ResponseVariant> = when (this) {
        ResponseMode.COMPARE -> ResponseVariant.entries
        ResponseMode.CONTROLLED -> listOf(ResponseVariant.CONTROLLED)
        ResponseMode.UNRESTRICTED -> listOf(ResponseVariant.UNRESTRICTED)
        ResponseMode.REASONING -> error("Reasoning mode must be handled by ReasoningRunner")
        ResponseMode.TEMPERATURE -> error("Temperature mode must be handled by TemperatureRunner")
        ResponseMode.MODEL_COMPARISON -> error("Model comparison mode must be handled by ModelComparisonRunner")
        ResponseMode.TOKENS_CONTEXT -> error("Token context demo must be handled by TokenContextDemoRunner")
    }
}

private val ResponseVariant.historyId: String
    get() = when (this) {
        ResponseVariant.UNRESTRICTED -> "unrestricted"
        ResponseVariant.CONTROLLED -> "controlled"
    }

private const val HISTORY_LOAD_WARNING =
    "Не удалось восстановить историю диалога: файл повреждён, недоступен или имеет неподдерживаемую версию. Начата пустая история."

private fun legacyTurnMetrics(variant: ResponseVariant, messages: List<LlmMessage>): List<TurnTokenMetrics> {
    val estimator = ApproximateChatTokenEstimator()
    val turns = mutableListOf<TurnTokenMetrics>()
    val activeHistory = mutableListOf<LlmMessage>()
    var index = 0
    while (index < messages.lastIndex) {
        val user = messages[index]
        val assistant = messages[index + 1]
        if (user.role == org.example.llm.LlmRole.USER && assistant.role == org.example.llm.LlmRole.ASSISTANT) {
            val current = estimator.estimateContent(user.content)
            val historyEstimate = estimator.estimateMessages(activeHistory)
            val context = estimator.estimateMessages(activeHistory + user)
            activeHistory += user
            activeHistory += assistant
            val provisional = TurnTokenMetrics(
                id = "legacy-${variant.name.lowercase()}-${turns.size + 1}",
                turnNumber = turns.size + 1,
                model = "unknown-legacy-model",
                userMessage = user.content,
                assistantMessage = assistant.content,
                estimatedCurrentMessageTokens = current,
                estimatedHistoryTokens = historyEstimate,
                estimatedContextTokens = context,
                contextBudget = ContextBudget(null, null, null, null, null, null),
                cumulativeTotals = ConversationTokenTotals(),
                overflowPolicy = ContextOverflowPolicy.REJECT,
                requiredTokens = context.tokens,
                pricingProfileId = null,
            )
            val candidate = turns + provisional
            turns += provisional.copy(cumulativeTotals = aggregateTotals(candidate, estimator.estimateMessages(activeHistory).tokens))
            index += 2
        } else {
            activeHistory += user
            index++
        }
    }
    return turns
}
