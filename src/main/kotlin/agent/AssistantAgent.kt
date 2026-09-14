package org.example.agent

import org.example.llm.*
import org.example.tokens.*

data class AssistantAgentResponse(
    val completion: CompletionResult,
    val tokenMetrics: TurnTokenMetrics,
    val memoryDiagnostics: AssistantMemoryDiagnostics,
)

/** Independent assistant pipeline. It never reads or writes LlmAgent/ContextManager history. */
class AssistantAgent(
    private val memoryManager: AssistantMemoryManager,
    private val tokenEstimator: TokenEstimator = ApproximateChatTokenEstimator(),
    private val profileProvider: (String) -> ModelContextProfile? = ModelContextProfiles::find,
    private val costCalculator: TokenCostCalculator = TokenCostCalculator(),
    private val clientProvider: () -> LlmClient,
) {
    private val completedMetrics = mutableListOf<TurnTokenMetrics>()

    suspend fun respond(
        prompt: String,
        model: String,
        maxTokens: Int,
        overflowPolicy: ContextOverflowPolicy,
        shortTermMessageLimit: Int,
        onDelta: (String) -> Unit = {},
        onMetrics: (TurnTokenMetrics) -> Unit = {},
    ): AssistantAgentResponse {
        val normalized = prompt.trim()
        require(normalized.isNotEmpty()) { "Запрос ассистенту не может быть пустым." }
        memoryManager.validateForStorage(normalized)
        val currentUserMessage = LlmMessage(LlmRole.USER, normalized)
        val memory = memoryManager.prepare(shortTermMessageLimit)
        val requestHistory = listOf(LlmMessage(LlmRole.SYSTEM, ASSISTANT_SYSTEM_INSTRUCTIONS)) + memory.historyMessages
        val profile = profileProvider(model)
        val preparation = try {
            ContextPreparer(tokenEstimator).prepare(
                history = requestHistory,
                currentUserMessage = currentUserMessage,
                profile = profile,
                requestedMaxOutputTokens = maxTokens,
                policy = overflowPolicy,
            )
        } catch (error: ContextLimitExceededException) {
            onMetrics(preparedMetrics(normalized, model, overflowPolicy, error.preparation, profile))
            throw error
        }
        val prepared = preparedMetrics(normalized, model, overflowPolicy, preparation, profile)
        onMetrics(prepared)
        val options = CompletionOptions(maxTokens = maxTokens)
        val completion = clientProvider().streamToCompletion(preparation.activeMessages, options, onDelta)
        completion.usage?.let(TokenCostCalculator::validateUsage)
        val billedProfile = completion.model?.let(profileProvider) ?: profile
        val cost = costCalculator.calculate(completion.usage, billedProfile)
        val withoutTotals = prepared.copy(
            model = completion.model ?: model,
            assistantMessage = completion.content,
            actualUsage = completion.usage,
            finishReason = completion.finishReason,
            turnCostUsd = cost,
            pricingProfileId = billedProfile?.id,
            pricingEffectiveDate = billedProfile?.effectiveDate,
            pricingSourceUrl = billedProfile?.sourceUrl,
        )
        val candidateTurns = completedMetrics + withoutTotals
        val historyTokens = tokenEstimator.estimateMessages(
            memory.state.shortTerm.map { LlmMessage(if (it.role == MemoryEntryRole.ASSISTANT) LlmRole.ASSISTANT else LlmRole.USER, it.text) } +
                currentUserMessage + LlmMessage(LlmRole.ASSISTANT, completion.content),
        ).tokens
        val completed = withoutTotals.copy(cumulativeTotals = aggregateTotals(candidateTurns, historyTokens, "assistant_memory"))

        // The LLM call is not a completed assistant turn until the whole pair is durably stored.
        memoryManager.commitShortTermPair(normalized, completion.content, shortTermMessageLimit)
        completedMetrics += completed
        onMetrics(completed)

        val excludedShortTermMessages = preparation.excludedMessageCount
        val diagnostics = memory.diagnostics.copy(layers = memory.diagnostics.layers.map { usage ->
            if (usage.layer != MemoryLayer.SHORT_TERM || excludedShortTermMessages == 0) usage else usage.copy(
                usedCount = (usage.usedCount - excludedShortTermMessages).coerceAtLeast(0),
                usedEntryIds = usage.usedEntryIds.drop(excludedShortTermMessages),
            )
        })
        return AssistantAgentResponse(completion, completed, diagnostics)
    }

    fun tokenMetricsSnapshot(): List<TurnTokenMetrics> = completedMetrics.toList()

    fun tokenTotalsSnapshot(): ConversationTokenTotals = aggregateTotals(
        completedMetrics,
        tokenEstimator.estimateMessages(memoryManager.state().shortTerm.map {
            LlmMessage(if (it.role == MemoryEntryRole.ASSISTANT) LlmRole.ASSISTANT else LlmRole.USER, it.text)
        }).tokens,
        "assistant_memory",
    )

    private fun preparedMetrics(
        prompt: String,
        model: String,
        overflowPolicy: ContextOverflowPolicy,
        preparation: org.example.tokens.ContextPreparationResult,
        profile: ModelContextProfile?,
    ) = TurnTokenMetrics(
        turnNumber = completedMetrics.size + 1,
        model = model,
        userMessage = prompt,
        estimatedCurrentMessageTokens = preparation.estimatedCurrentMessageTokens,
        estimatedHistoryTokens = preparation.estimatedHistoryTokens,
        estimatedContextTokens = preparation.estimatedContextTokens,
        contextBudget = preparation.budget,
        cumulativeTotals = tokenTotalsSnapshot(),
        overflowPolicy = overflowPolicy,
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
