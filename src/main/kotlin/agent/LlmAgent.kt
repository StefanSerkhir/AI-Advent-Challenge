package org.example.agent

import org.example.llm.*
import org.example.tokens.*

/** A user request handled by an [Agent]. */
data class AgentRequest(
    val prompt: String,
    val options: CompletionOptions = CompletionOptions(),
    val historyEnabled: Boolean = true,
    val model: String = "unknown",
    val overflowPolicy: ContextOverflowPolicy = ContextOverflowPolicy.REJECT,
)

/** The agent response keeps the provider result, including model and token metadata. */
data class AgentResponse(
    val completion: CompletionResult,
    val tokenMetrics: TurnTokenMetrics,
) {
    val content: String
        get() = completion.content
}

/**
 * An application-level entity that turns a user request into an answer.
 *
 * The UI and use cases depend on this contract instead of invoking an LLM API client directly.
 */
interface Agent {
    suspend fun respond(
        request: AgentRequest,
        onDelta: (accumulatedText: String) -> Unit = {},
        onMetrics: (TurnTokenMetrics) -> Unit = {},
    ): AgentResponse
}

/**
 * A simple conversational agent. It owns its dialogue memory, builds the API messages,
 * calls the configured LLM and records only successfully completed exchanges.
 *
 * One instance represents one independent conversation branch. It is expected to be used
 * by a single request worker at a time.
 */
class LlmAgent(
    initialHistory: List<LlmMessage> = emptyList(),
    private val persistHistory: (List<LlmMessage>) -> Unit = {},
    initialTurnMetrics: List<TurnTokenMetrics> = emptyList(),
    private val persistConversation: ((List<LlmMessage>, List<TurnTokenMetrics>) -> Unit)? = null,
    private val tokenEstimator: TokenEstimator = ApproximateChatTokenEstimator(),
    private val profileProvider: (String) -> ModelContextProfile? = ModelContextProfiles::find,
    private val costCalculator: TokenCostCalculator = TokenCostCalculator(),
    private val clientProvider: () -> LlmClient,
) : Agent {
    private val history = initialHistory.toMutableList()
    private val completedMetrics = initialTurnMetrics.toMutableList()
    private val runtimeMetrics = mutableListOf<TurnTokenMetrics>()

    override suspend fun respond(
        request: AgentRequest,
        onDelta: (accumulatedText: String) -> Unit,
        onMetrics: (TurnTokenMetrics) -> Unit,
    ): AgentResponse {
        val prompt = request.prompt.trim()
        require(prompt.isNotEmpty()) { "Запрос агенту не может быть пустым." }

        val userMessage = LlmMessage(LlmRole.USER, prompt)
        val requestHistory = if (request.historyEnabled) history.toList() else emptyList()
        val profile = profileProvider(request.model)
        val preparation = try {
            ContextPreparer(tokenEstimator).prepare(
                history = requestHistory,
                currentUserMessage = userMessage,
                profile = profile,
                requestedMaxOutputTokens = request.options.maxTokens,
                policy = request.overflowPolicy,
            )
        } catch (error: ContextLimitExceededException) {
            onMetrics(preparedMetrics(request, error.preparation, profile))
            throw error
        }
        val prepared = preparedMetrics(request, preparation, profile)
        onMetrics(prepared)
        val completion = clientProvider().streamToCompletion(preparation.activeMessages, request.options, onDelta)
        completion.usage?.let(TokenCostCalculator::validateUsage)
        val billedProfile = if (completion.model != null) profileProvider(completion.model) else profile

        val assistantMessage = LlmMessage(LlmRole.ASSISTANT, completion.content)
        val candidateHistory = if (request.historyEnabled) history + userMessage + assistantMessage else history.toList()
        val baseTurns = if (request.historyEnabled) completedMetrics.toList() else runtimeMetrics.toList()
        val cost = costCalculator.calculate(completion.usage, billedProfile)
        val withoutTotals = prepared.copy(
            model = completion.model ?: request.model,
            assistantMessage = completion.content,
            actualUsage = completion.usage,
            finishReason = completion.finishReason,
            turnCostUsd = cost,
            pricingProfileId = billedProfile?.id,
            pricingEffectiveDate = billedProfile?.effectiveDate,
            pricingSourceUrl = billedProfile?.sourceUrl,
        )
        val currentHistoryTokens = if (request.historyEnabled) {
            tokenEstimator.estimateMessages(candidateHistory).tokens
        } else {
            0
        }
        val candidateTurns = baseTurns + withoutTotals
        val totals = aggregateTotals(
            candidateTurns,
            currentHistoryTokens,
            scope = if (request.historyEnabled) "saved_conversation" else "runtime_without_history",
        )
        val completed = withoutTotals.copy(cumulativeTotals = totals)

        if (request.historyEnabled) {
            val completedHistory = candidateHistory
            val turns = completedMetrics + completed
            persistConversation?.invoke(completedHistory, turns) ?: persistHistory(completedHistory)
            history.clear()
            history.addAll(completedHistory)
            completedMetrics.clear()
            completedMetrics.addAll(turns)
        } else {
            runtimeMetrics += completed
        }

        onMetrics(completed)
        return AgentResponse(completion, completed)
    }

    fun historySnapshot(): List<LlmMessage> = history.toList()

    fun completedTurnCount(): Int = history.count { it.role == LlmRole.ASSISTANT }

    fun tokenMetricsSnapshot(): List<TurnTokenMetrics> = completedMetrics.toList()

    fun conversationTotals(): ConversationTokenTotals = aggregateTotals(
        completedMetrics,
        tokenEstimator.estimateMessages(history).tokens,
    )

    fun clearHistory() {
        history.clear()
        completedMetrics.clear()
    }

    private fun preparedMetrics(
        request: AgentRequest,
        preparation: ContextPreparationResult,
        profile: ModelContextProfile?,
    ): TurnTokenMetrics {
        val baseTurns = if (request.historyEnabled) completedMetrics else runtimeMetrics
        val baseHistoryTokens = if (request.historyEnabled) tokenEstimator.estimateMessages(history).tokens else 0
        return TurnTokenMetrics(
            turnNumber = baseTurns.size + 1,
            model = request.model,
            userMessage = request.prompt.trim(),
            estimatedCurrentMessageTokens = preparation.estimatedCurrentMessageTokens,
            estimatedHistoryTokens = preparation.estimatedHistoryTokens,
            estimatedContextTokens = preparation.estimatedContextTokens,
            contextBudget = preparation.budget,
            cumulativeTotals = aggregateTotals(baseTurns, baseHistoryTokens,
                if (request.historyEnabled) "saved_conversation" else "runtime_without_history"),
            overflowPolicy = request.overflowPolicy,
            excludedMessageCount = preparation.excludedMessageCount,
            excludedEstimatedTokens = preparation.excludedEstimatedTokens,
            requiredTokens = preparation.requiredTokens,
            exceededByTokens = preparation.exceededByTokens,
            pricingProfileId = profile?.id,
            pricingEffectiveDate = profile?.effectiveDate,
            pricingSourceUrl = profile?.sourceUrl,
            contextProfileSimulated = profile?.simulated == true,
        )
    }
}
