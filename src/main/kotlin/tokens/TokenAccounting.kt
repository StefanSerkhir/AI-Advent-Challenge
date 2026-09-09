package org.example.tokens

import org.example.llm.LlmMessage
import org.example.llm.LlmRole
import org.example.llm.TokenUsage
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*
import kotlin.math.ceil

data class TokenEstimate(
    val tokens: Int,
    val exact: Boolean,
    val estimatorId: String,
    val warning: String? = null,
) {
    init { require(tokens >= 0) }
}

/** Tokenization is an injected boundary: the agent never depends on a tokenizer implementation. */
interface TokenEstimator {
    fun estimateContent(content: String): TokenEstimate
    fun estimateMessages(messages: List<LlmMessage>): TokenEstimate
}

/**
 * Replaceable fallback for models whose current tokenizer is not reliably available on the JVM.
 * It uses Unicode script runs and punctuation boundaries, plus explicit Chat Completions framing.
 * It is intentionally reported as approximate, never as an exact token count.
 */
class ApproximateChatTokenEstimator : TokenEstimator {
    override fun estimateContent(content: String): TokenEstimate {
        if (content.isEmpty()) return estimate(0)
        var units = 0.0
        var latinRun = 0
        fun flushLatin() {
            if (latinRun > 0) units += ceil(latinRun / 3.6)
            latinRun = 0
        }
        content.codePoints().forEach { codePoint ->
            when {
                Character.isWhitespace(codePoint) -> flushLatin()
                codePoint in 0x21..0x7e && Character.isLetterOrDigit(codePoint) -> latinRun++
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.CYRILLIC -> {
                    flushLatin(); units += 0.55
                }
                Character.isLetterOrDigit(codePoint) -> {
                    flushLatin(); units += 0.8
                }
                codePoint > 0xffff -> {
                    flushLatin(); units += 2.0
                }
                else -> {
                    flushLatin(); units += 1.0
                }
            }
        }
        flushLatin()
        return estimate(ceil(units).toInt().coerceAtLeast(1))
    }

    override fun estimateMessages(messages: List<LlmMessage>): TokenEstimate {
        if (messages.isEmpty()) return estimate(0)
        val framing = CHAT_PRIMING_TOKENS + messages.size * TOKENS_PER_MESSAGE
        val roles = messages.sumOf { estimateContent(it.role.apiValue).tokens }
        val content = messages.sumOf { estimateContent(it.content).tokens }
        return estimate(Math.addExact(framing, Math.addExact(roles, content)))
    }

    private fun estimate(tokens: Int) = TokenEstimate(tokens, exact = false, ESTIMATOR_ID, WARNING)

    companion object {
        const val ESTIMATOR_ID = "unicode-chat-fallback-v1"
        const val WARNING = "Для выбранной модели нет подтверждённого точного tokenizer на JVM; показана приблизительная оценка с учётом ролей и framing Chat Completions."
        const val TOKENS_PER_MESSAGE = 3
        const val CHAT_PRIMING_TOKENS = 3
    }
}

data class ModelPricing(
    val uncachedInputPerMillionUsd: BigDecimal,
    val cachedInputPerMillionUsd: BigDecimal,
    val cacheWriteMultiplier: BigDecimal,
    val outputPerMillionUsd: BigDecimal,
    val highTierInputThreshold: Int? = null,
    val highTierInputMultiplier: BigDecimal = BigDecimal.ONE,
    val highTierOutputMultiplier: BigDecimal = BigDecimal.ONE,
)

data class ModelContextProfile(
    val id: String,
    val modelId: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val pricing: ModelPricing,
    val effectiveDate: LocalDate,
    val sourceUrl: String,
    val simulated: Boolean = false,
)

object ModelContextProfiles {
    private val commonDate = LocalDate.of(2026, 9, 9)
    private const val solSource = "https://developers.openai.com/api/docs/models/gpt-5.6-sol"
    private const val openAi41MiniSource = "https://developers.openai.com/api/docs/models/gpt-4.1-mini"
    private const val deepSeekSource = "https://api-docs.deepseek.com/quick_start/pricing/"
    private fun profile(id: String, model: String, input: String, cached: String, output: String, source: String) =
        ModelContextProfile(
            id = id,
            modelId = model,
            contextWindow = 1_050_000,
            maxOutputTokens = 128_000,
            pricing = ModelPricing(
                uncachedInputPerMillionUsd = input.toBigDecimal(),
                cachedInputPerMillionUsd = cached.toBigDecimal(),
                cacheWriteMultiplier = "1.25".toBigDecimal(),
                outputPerMillionUsd = output.toBigDecimal(),
                highTierInputThreshold = 272_000,
                highTierInputMultiplier = "2".toBigDecimal(),
                highTierOutputMultiplier = "1.5".toBigDecimal(),
            ),
            effectiveDate = commonDate,
            sourceUrl = source,
        )

    val GPT_5_6_LUNA = profile("openai-gpt-5.6-luna-2026-09-09", "gpt-5.6-luna", "0.20", "0.02", "1.20", "https://developers.openai.com/api/docs/models/gpt-5.6-luna")
    val GPT_5_6_TERRA = profile("openai-gpt-5.6-terra-2026-09-09", "gpt-5.6-terra", "2.00", "0.20", "12.00", "https://developers.openai.com/api/docs/models/gpt-5.6-terra")
    val GPT_5_6_SOL = profile("openai-gpt-5.6-sol-2026-09-09", "gpt-5.6-sol", "4.00", "0.40", "20.00", solSource)
    val GPT_4_1_MINI = ModelContextProfile(
        id = "openai-gpt-4.1-mini-2026-09-09",
        modelId = "gpt-4.1-mini",
        contextWindow = 1_047_576,
        maxOutputTokens = 32_768,
        pricing = ModelPricing(
            uncachedInputPerMillionUsd = "0.40".toBigDecimal(),
            cachedInputPerMillionUsd = "0.10".toBigDecimal(),
            cacheWriteMultiplier = BigDecimal.ONE,
            outputPerMillionUsd = "1.60".toBigDecimal(),
        ),
        effectiveDate = commonDate,
        sourceUrl = openAi41MiniSource,
    )

    private fun deepSeekV4FlashProfile(peak: Boolean) = ModelContextProfile(
        id = "deepseek-v4-flash-${if (peak) "peak" else "off-peak"}-2026-08-16",
        modelId = "deepseek-v4-flash",
        contextWindow = 1_048_576,
        maxOutputTokens = 384_000,
        pricing = ModelPricing(
            uncachedInputPerMillionUsd = (if (peak) "0.44" else "0.22").toBigDecimal(),
            cachedInputPerMillionUsd = (if (peak) "0.014" else "0.007").toBigDecimal(),
            cacheWriteMultiplier = BigDecimal.ONE,
            outputPerMillionUsd = (if (peak) "1.32" else "0.66").toBigDecimal(),
        ),
        effectiveDate = LocalDate.of(2026, 8, 16),
        sourceUrl = deepSeekSource,
    )

    val DEEPSEEK_V4_FLASH_PEAK = deepSeekV4FlashProfile(peak = true)
    val DEEPSEEK_V4_FLASH_OFF_PEAK = deepSeekV4FlashProfile(peak = false)

    private val profiles = listOf(GPT_5_6_LUNA, GPT_5_6_TERRA, GPT_5_6_SOL, GPT_4_1_MINI)

    fun find(modelId: String): ModelContextProfile? = find(modelId, Instant.now())

    internal fun find(modelId: String, requestTime: Instant): ModelContextProfile? = when {
        modelId == "gpt-5.6" -> GPT_5_6_SOL
        modelId == "deepseek-v4-flash" || modelId == "deepseek-chat" || modelId == "deepseek-reasoner" ->
            deepSeekV4FlashProfile(isDeepSeekPeak(requestTime))
        else -> profiles.firstOrNull { profile ->
            modelId == profile.modelId || (modelId.startsWith(profile.modelId + "-") &&
                modelId.drop(profile.modelId.length + 1).firstOrNull()?.isDigit() == true)
        }
    }

    /** Peak: Monday-Friday, 01:00-04:00 and 06:00-10:00 UTC. */
    private fun isDeepSeekPeak(requestTime: Instant): Boolean {
        val utc = requestTime.atZone(ZoneOffset.UTC)
        if (utc.dayOfWeek == DayOfWeek.SATURDAY || utc.dayOfWeek == DayOfWeek.SUNDAY) return false
        return utc.hour in 1..<4 || utc.hour in 6..<10
    }

    fun demo(contextWindow: Int = 6_000, maxOutputTokens: Int = 700): ModelContextProfile =
        GPT_5_6_SOL.copy(
            id = "demo-gpt-5.6-sol-${contextWindow}-2026-09-09",
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            simulated = true,
        )
}

class InvalidTokenUsageException(message: String) : IllegalArgumentException(message)

class TokenCostCalculator {
    fun calculate(usage: TokenUsage?, profile: ModelContextProfile?): BigDecimal? {
        if (usage == null || profile == null) return null
        validateUsage(usage)
        val pricing = profile.pricing
        val cached = usage.cachedPromptTokens
        val cacheWrite = usage.cacheWritePromptTokens
        val uncached = usage.promptTokens - cached - cacheWrite
        val highTier = pricing.highTierInputThreshold?.let { usage.promptTokens > it } == true
        val inputMultiplier = if (highTier) pricing.highTierInputMultiplier else BigDecimal.ONE
        val outputMultiplier = if (highTier) pricing.highTierOutputMultiplier else BigDecimal.ONE
        val inputCost = tokensCost(uncached, pricing.uncachedInputPerMillionUsd)
            .add(tokensCost(cached, pricing.cachedInputPerMillionUsd))
            .add(tokensCost(cacheWrite, pricing.uncachedInputPerMillionUsd.multiply(pricing.cacheWriteMultiplier)))
            .multiply(inputMultiplier)
        // Reasoning tokens are already included in completionTokens by the provider.
        val outputCost = tokensCost(usage.completionTokens, pricing.outputPerMillionUsd).multiply(outputMultiplier)
        return inputCost.add(outputCost).stripTrailingZeros()
    }

    private fun tokensCost(tokens: Int, price: BigDecimal): BigDecimal =
        BigDecimal.valueOf(tokens.toLong()).multiply(price).divide(MILLION, MONEY_CONTEXT)

    companion object {
        private val MILLION = BigDecimal("1000000")
        private val MONEY_CONTEXT = MathContext(24, RoundingMode.HALF_EVEN)

        fun validateUsage(usage: TokenUsage) {
            if (listOf(usage.promptTokens, usage.completionTokens, usage.totalTokens,
                    usage.cachedPromptTokens, usage.cacheWritePromptTokens, usage.reasoningTokens).any { it < 0 }) {
                throw InvalidTokenUsageException("Провайдер вернул отрицательные значения usage.")
            }
            if (usage.cachedPromptTokens.toLong() + usage.cacheWritePromptTokens > usage.promptTokens) {
                throw InvalidTokenUsageException("Cached и cache-write tokens превышают input usage.")
            }
            if (usage.reasoningTokens > usage.completionTokens) {
                throw InvalidTokenUsageException("Reasoning tokens превышают output usage.")
            }
        }
    }
}

enum class ContextOverflowPolicy { REJECT, DROP_OLDEST }

data class ContextBudget(
    val contextWindow: Int?,
    val requestedMaxOutputTokens: Int?,
    val reservedOutputTokens: Int?,
    val availableInputTokens: Int?,
    val estimatedContextUsagePercent: BigDecimal?,
    val estimatedRemainingInputTokens: Int?,
)

data class ContextPreparationResult(
    val activeMessages: List<LlmMessage>,
    val estimatedCurrentMessageTokens: TokenEstimate,
    val estimatedHistoryTokens: TokenEstimate,
    val estimatedContextTokens: TokenEstimate,
    val budget: ContextBudget,
    val overflowPolicy: ContextOverflowPolicy,
    val excludedMessageCount: Int = 0,
    val excludedEstimatedTokens: Int = 0,
    val requiredTokens: Int,
    val exceededByTokens: Int = 0,
)

class ContextLimitExceededException(
    val requiredTokens: Int,
    val contextWindow: Int,
    val reservedOutputTokens: Int,
    val availableInputTokens: Int,
    val exceededByTokens: Int,
    val overflowPolicy: ContextOverflowPolicy,
    val preparation: ContextPreparationResult,
) : RuntimeException(
    "Контекст не помещается: требуется $requiredTokens input-токенов, доступно $availableInputTokens " +
        "из окна $contextWindow при резерве ответа $reservedOutputTokens; превышение $exceededByTokens. " +
        "Политика: ${overflowPolicy.name}.",
)

class ContextPreparer(private val estimator: TokenEstimator) {
    fun prepare(
        history: List<LlmMessage>,
        currentUserMessage: LlmMessage,
        profile: ModelContextProfile?,
        requestedMaxOutputTokens: Int?,
        policy: ContextOverflowPolicy,
    ): ContextPreparationResult {
        require(currentUserMessage.role == LlmRole.USER)
        val currentEstimate = estimator.estimateContent(currentUserMessage.content)
        val originalHistoryEstimate = estimator.estimateMessages(history)
        if (profile == null) {
            val context = estimator.estimateMessages(history + currentUserMessage)
            return ContextPreparationResult(
                history + currentUserMessage, currentEstimate, originalHistoryEstimate, context,
                ContextBudget(null, requestedMaxOutputTokens, null, null, null, null), policy,
                requiredTokens = context.tokens,
            )
        }
        val reserve = requestedMaxOutputTokens ?: profile.maxOutputTokens
        require(reserve > 0) { "Резерв output tokens должен быть больше нуля." }
        require(reserve <= profile.maxOutputTokens) {
            "Запрошено $reserve output-токенов, максимум модели — ${profile.maxOutputTokens}."
        }
        val available = profile.contextWindow - reserve
        require(available >= 0) { "Резерв ответа превышает контекстное окно модели." }
        var activeHistory = history.toList()
        var context = estimator.estimateMessages(activeHistory + currentUserMessage)
        val originalContextTokens = context.tokens
        var excludedMessages = 0
        var excludedTokens = 0
        if (context.tokens > available && policy == ContextOverflowPolicy.DROP_OLDEST) {
            val pairStarts = removablePairStarts(activeHistory)
            val removedIndexes = mutableSetOf<Int>()
            for (start in pairStarts) {
                if (context.tokens <= available) break
                removedIndexes += start
                removedIndexes += start + 1
                excludedMessages += 2
                val candidate = activeHistory.filterIndexed { index, _ -> index !in removedIndexes }
                context = estimator.estimateMessages(candidate + currentUserMessage)
            }
            activeHistory = activeHistory.filterIndexed { index, _ -> index !in removedIndexes }
            excludedTokens = (originalContextTokens - context.tokens).coerceAtLeast(0)
        }
        val activeExceeded = (context.tokens - available).coerceAtLeast(0)
        val originalExceeded = (originalContextTokens - available).coerceAtLeast(0)
        val budget = ContextBudget(
            contextWindow = profile.contextWindow,
            requestedMaxOutputTokens = requestedMaxOutputTokens,
            reservedOutputTokens = reserve,
            availableInputTokens = available,
            estimatedContextUsagePercent = if (available == 0) null else
                BigDecimal.valueOf(context.tokens.toLong()).multiply(BigDecimal("100"))
                    .divide(BigDecimal.valueOf(available.toLong()), 4, RoundingMode.HALF_UP),
            estimatedRemainingInputTokens = (available - context.tokens).coerceAtLeast(0),
        )
        val result = ContextPreparationResult(
            activeMessages = activeHistory + currentUserMessage,
            estimatedCurrentMessageTokens = currentEstimate,
            estimatedHistoryTokens = originalHistoryEstimate,
            estimatedContextTokens = context,
            budget = budget,
            overflowPolicy = policy,
            excludedMessageCount = excludedMessages,
            excludedEstimatedTokens = excludedTokens,
            requiredTokens = if (excludedMessages > 0) originalContextTokens else context.tokens,
            exceededByTokens = if (excludedMessages > 0) originalExceeded else activeExceeded,
        )
        if (activeExceeded > 0) {
            throw ContextLimitExceededException(context.tokens, profile.contextWindow, reserve, available, activeExceeded, policy, result)
        }
        return result
    }

    private fun removablePairStarts(history: List<LlmMessage>): List<Int> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index < history.lastIndex) {
            if (history[index].role == LlmRole.USER && history[index + 1].role == LlmRole.ASSISTANT) {
                starts += index
                index += 2
            } else {
                // Legacy orphan messages are retained deterministically; only intact pairs are removable.
                index++
            }
        }
        return starts
    }
}

data class ConversationTokenTotals(
    val completedTurns: Int = 0,
    val currentHistoryTokens: Int = 0,
    val cumulativeApiInputTokens: Long? = 0,
    val cumulativeOutputTokens: Long? = 0,
    val cumulativeReasoningTokens: Long? = 0,
    val cumulativeTotalTokens: Long? = 0,
    val cumulativeCachedInputTokens: Long? = 0,
    val cumulativeCacheWriteInputTokens: Long? = 0,
    val cumulativeCostUsd: BigDecimal? = BigDecimal.ZERO,
    val contextTruncations: Int = 0,
    val scope: String = "saved_conversation",
)

data class TurnTokenMetrics(
    val id: String = UUID.randomUUID().toString(),
    val turnNumber: Int,
    val model: String,
    val userMessage: String,
    val assistantMessage: String? = null,
    val estimatedCurrentMessageTokens: TokenEstimate,
    val estimatedHistoryTokens: TokenEstimate,
    val estimatedContextTokens: TokenEstimate,
    val actualUsage: TokenUsage? = null,
    val contextBudget: ContextBudget,
    val finishReason: String? = null,
    val turnCostUsd: BigDecimal? = null,
    val cumulativeTotals: ConversationTokenTotals,
    val overflowPolicy: ContextOverflowPolicy,
    val excludedMessageCount: Int = 0,
    val excludedEstimatedTokens: Int = 0,
    val requiredTokens: Int,
    val exceededByTokens: Int = 0,
    val pricingProfileId: String? = null,
    val pricingEffectiveDate: LocalDate? = null,
    val pricingSourceUrl: String? = null,
    val contextProfileSimulated: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val tokenizerApproximate: Boolean get() = !estimatedContextTokens.exact
}

fun aggregateTotals(
    turns: List<TurnTokenMetrics>,
    currentHistoryTokens: Int,
    scope: String = "saved_conversation",
): ConversationTokenTotals {
    fun sumOrNull(selector: (TokenUsage) -> Int): Long? {
        if (turns.any { it.actualUsage == null }) return null
        return turns.sumOf { selector(requireNotNull(it.actualUsage)).toLong() }
    }
    val costs = turns.map { it.turnCostUsd }
    return ConversationTokenTotals(
        completedTurns = turns.size,
        currentHistoryTokens = currentHistoryTokens,
        cumulativeApiInputTokens = sumOrNull(TokenUsage::promptTokens),
        cumulativeOutputTokens = sumOrNull(TokenUsage::completionTokens),
        cumulativeReasoningTokens = sumOrNull(TokenUsage::reasoningTokens),
        cumulativeTotalTokens = sumOrNull(TokenUsage::totalTokens),
        cumulativeCachedInputTokens = sumOrNull(TokenUsage::cachedPromptTokens),
        cumulativeCacheWriteInputTokens = sumOrNull(TokenUsage::cacheWritePromptTokens),
        cumulativeCostUsd = if (costs.any { it == null }) null else costs.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add),
        contextTruncations = turns.count { it.excludedMessageCount > 0 },
        scope = scope,
    )
}
