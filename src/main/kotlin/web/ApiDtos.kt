package org.example.web

import kotlinx.serialization.Serializable
import org.example.agent.ContextDiagnostics
import org.example.agent.ContextStrategy
import org.example.app.*
import org.example.llm.LlmKind
import org.example.llm.LlmMessage
import org.example.llm.LlmModel
import org.example.llm.LlmModels
import org.example.tokens.ContextOverflowPolicy
import org.example.tokens.ConversationTokenTotals
import org.example.tokens.TurnTokenMetrics

@Serializable
data class SettingsDto(
    val provider: String, val model: String, val mode: String,
    val maxTokens: Int, val maxWords: Int, val bulletCount: Int,
    val stopSequence: String?, val historyEnabled: Boolean,
    val contextOverflowPolicy: String = "REJECT",
    val contextStrategy: String = "SLIDING_WINDOW",
    val recentMessagesLimit: Int = 10,
)

@Serializable
data class SettingsCommand(val expectedSettingsVersion: Long, val settings: SettingsDto)
@Serializable
class KeyCommand(val expectedSettingsVersion: Long, val provider: String, val key: String)
@Serializable
data class StartCommand(val requestId: String, val expectedSettingsVersion: Long, val prompt: String = "", val demo: String? = null)
@Serializable
data class StartReply(val operationId: Long)
@Serializable
data class ContextMutationCommand(val expectedSettingsVersion: Long)
@Serializable
data class SwitchBranchCommand(val expectedSettingsVersion: Long, val branchId: String)
@Serializable
data class ErrorDto(val code: String, val message: String)
@Serializable
data class ModelDto(val id: String, val title: String)
@Serializable
data class ProviderDto(val id: String, val title: String, val hasKey: Boolean, val models: List<ModelDto>)
@Serializable
data class ModeDto(
    val id: String, val title: String, val description: String,
    val independentContext: Boolean = false, val connectionLocked: Boolean = false,
    val usesTokenLimit: Boolean = false, val usesTextConstraints: Boolean = false,
    val usesHistory: Boolean = false,
)
@Serializable
data class ProgressDto(val current: Int, val total: Int, val label: String)
@Serializable
data class OperationDto(val id: Long, val progress: ProgressDto)
@Serializable
data class NoticeDto(val kind: String, val message: String)
@Serializable
data class MetricsDto(
    val characters: Int?, val words: Int?, val completionTokens: Int?, val finishReason: String?,
    val promptTokens: Int?, val reasoningTokens: Int?, val totalTokens: Int?,
    val elapsedMillis: Long?, val estimatedCostUsd: Double?,
    val cachedInputTokens: Int? = null, val cacheWriteInputTokens: Int? = null,
)
@Serializable
data class TokenValueDto(val value: Long?, val source: String, val approximate: Boolean = false)
@Serializable
data class DecimalValueDto(val value: String?, val source: String, val approximate: Boolean = false)
@Serializable
data class ConversationTokenTotalsDto(
    val completedTurns: TokenValueDto,
    val currentHistoryTokens: TokenValueDto,
    val cumulativeApiInputTokens: TokenValueDto,
    val cumulativeOutputTokens: TokenValueDto,
    val cumulativeReasoningTokens: TokenValueDto,
    val cumulativeTotalTokens: TokenValueDto,
    val cumulativeCachedInputTokens: TokenValueDto,
    val cumulativeCacheWriteInputTokens: TokenValueDto,
    val cumulativeCostUsd: DecimalValueDto,
    val contextTruncations: TokenValueDto,
    val scope: String,
)
@Serializable
data class TurnTokenMetricsDto(
    val id: String,
    val turnNumber: Int,
    val model: String,
    val estimatedCurrentMessageTokens: TokenValueDto,
    val estimatedHistoryTokens: TokenValueDto,
    val estimatedContextTokens: TokenValueDto,
    val actualInputTokens: TokenValueDto,
    val actualOutputTokens: TokenValueDto,
    val reasoningTokens: TokenValueDto,
    val cachedInputTokens: TokenValueDto,
    val cacheWriteInputTokens: TokenValueDto,
    val totalTokens: TokenValueDto,
    val contextWindow: TokenValueDto,
    val requestedMaxOutputTokens: TokenValueDto,
    val reservedOutputTokens: TokenValueDto,
    val availableInputTokens: TokenValueDto,
    val contextUsagePercent: DecimalValueDto,
    val estimatedRemainingInputTokens: TokenValueDto,
    val finishReason: String?,
    val turnCostUsd: DecimalValueDto,
    val cumulativeTotals: ConversationTokenTotalsDto,
    val overflowPolicy: String,
    val excludedMessageCount: TokenValueDto,
    val excludedEstimatedTokens: TokenValueDto,
    val requiredTokens: TokenValueDto,
    val exceededByTokens: TokenValueDto,
    val tokenizerId: String,
    val tokenizerWarning: String?,
    val pricingProfileId: String?,
    val pricingEffectiveDate: String?,
    val pricingSourceUrl: String?,
    val contextProfileSimulated: Boolean,
    val createdAtEpochMillis: Long,
)
@Serializable
data class TokenConversationDto(val turns: List<TurnTokenMetricsDto>, val totals: ConversationTokenTotalsDto)
@Serializable
data class OutputDto(
    val id: String, val title: String, val kind: String, val content: String?,
    val error: String?, val model: String?, val metrics: MetricsDto, val streaming: Boolean,
    val tokenMetrics: TurnTokenMetricsDto? = null,
    val contextStrategy: String? = null,
    val branchId: String? = null,
    val branchName: String? = null,
)
@Serializable
data class ExchangeDto(
    val id: Long, val prompt: String, val mode: String, val status: String,
    val error: String?, val outputs: List<OutputDto>, val estimatedTotalCostUsd: Double?, val evaluationNote: String?,
    val errorCode: String? = null,
    val sentinelInPermanentHistory: Boolean? = null,
    val sentinelInActiveContext: Boolean? = null,
)
@Serializable
data class HistoryMessageDto(val role: String, val content: String)
@Serializable
data class FactUsageDto(val requests: Int, val inputTokens: Long, val outputTokens: Long, val totalTokens: Long)
@Serializable
data class CheckpointDto(val id: String, val createdAtEpochMillis: Long, val messages: List<HistoryMessageDto>)
@Serializable
data class ContextBranchDto(val id: String, val name: String, val messages: List<HistoryMessageDto>)
@Serializable
data class ContextDto(
    val strategy: String,
    val recentMessagesLimit: Int,
    val facts: Map<String, String>,
    val factUsage: FactUsageDto,
    val checkpoint: CheckpointDto?,
    val branches: List<ContextBranchDto>,
    val activeBranchId: String,
)
@Serializable
data class HistoryDetailsDto(
    val counts: HistoryDto,
    val branches: Map<String, List<HistoryMessageDto>>,
    val tokenConversations: Map<String, TokenConversationDto> = emptyMap(),
    val context: ContextDto,
)
@Serializable
data class HistoryDto(val unrestricted: Int, val controlled: Int)
@Serializable
data class StateDto(
    val revision: Long, val settingsVersion: Long, val settings: SettingsDto,
    val providers: List<ProviderDto>, val modes: List<ModeDto>, val history: HistoryDto,
    val exchanges: List<ExchangeDto>, val operation: OperationDto?, val notice: NoticeDto?,
    val priceDate: String = MODEL_PRICE_DATE,
    val tokenConversations: Map<String, TokenConversationDto> = emptyMap(),
    val context: ContextDto,
)

fun AppSettings.toDto() = SettingsDto(
    llmKind.name, model, responseMode.cliValue, maxTokens, maxWords, bulletCount,
    stopSequence, historyEnabled, contextOverflowPolicy.name, contextStrategy.name,
    recentMessagesLimit,
)

fun SettingsDto.toSettings(): AppSettings {
    val kind = LlmKind.entries.firstOrNull { it.name == provider }
        ?: throw ApiProblem(400, "validation", "Неизвестный провайдер.")
    val responseMode = ResponseMode.from(mode)
        ?: throw ApiProblem(400, "validation", "Неизвестный режим.")
    if (maxTokens <= 0 || maxWords <= 0 || bulletCount <= 0 || recentMessagesLimit <= 0) {
        throw ApiProblem(400, "validation", "Лимиты и параметры управления контекстом должны быть целыми числами больше нуля.")
    }
    val overflowPolicy = runCatching { ContextOverflowPolicy.valueOf(contextOverflowPolicy) }
        .getOrElse { throw ApiProblem(400, "validation", "Неизвестная политика переполнения контекста.") }
    val strategy = ContextStrategy.from(contextStrategy)
        ?: throw ApiProblem(400, "validation", "Неизвестная стратегия контекста.")
    return AppSettings(
        llmKind = kind,
        model = model,
        responseMode = responseMode,
        maxTokens = maxTokens,
        maxWords = maxWords,
        bulletCount = bulletCount,
        stopSequence = stopSequence,
        historyEnabled = historyEnabled,
        contextOverflowPolicy = overflowPolicy,
        contextStrategy = strategy,
        recentMessagesLimit = recentMessagesLimit,
    ).also(::validateSettings)
}

val modes = listOf(
    ModeDto("compare", "Сравнение ответов", "Два ответа: свободный и с вашими ограничениями. Для каждого сохраняется отдельная ветка истории.", usesTokenLimit = true, usesTextConstraints = true, usesHistory = true),
    ModeDto("controlled", "С ограничениями", "Маркированный список с лимитом слов, пунктов, токенов и stop sequence.", usesTokenLimit = true, usesTextConstraints = true, usesHistory = true),
    ModeDto("unrestricted", "Простой агент", "Агент учитывает историю, токены, контекстный бюджет и фактическую стоимость каждого завершённого вызова.", usesTokenLimit = true, usesHistory = true),
    ModeDto("reasoning", "4 способа рассуждения", "Прямой ответ, пошаговое решение, созданный промпт и группа экспертов. Затем — оценка точности. 6 вызовов, независимые контексты.", independentContext = true),
    ModeDto("temperature", "Сравнение температуры", "Temperature 0, 0.7 и 1.2, затем оценка точности, креативности и разнообразия. Для Luna используется gpt-4.1-mini. 4 независимых вызова.", independentContext = true),
    ModeDto("models", "Сравнение моделей GPT-5.6", "Luna, Terra и Sol последовательно отвечают на один запрос с reasoning_effort=medium. Sol оценивает анонимные ответы A/B/C. Нужен ключ OpenAI.", independentContext = true, connectionLocked = true, usesTokenLimit = true),
    ModeDto("tokens", "Токены и контекст", "Локальные детерминированные сценарии: короткий, длинный диалог и безопасная симуляция переполнения 6K. API-ключ не нужен.", independentContext = true),
)

private fun estimated(value: Int) = TokenValueDto(value.toLong(), "estimate", approximate = true)
private fun actual(value: Int?) = TokenValueDto(value?.toLong(), "actual_api")
private fun profile(value: Int?) = TokenValueDto(value?.toLong(), "model_profile")
private fun requested(value: Int?) = TokenValueDto(value?.toLong(), "request")
private fun derived(value: Int?) = TokenValueDto(value?.toLong(), "derived_estimate", approximate = true)
private fun cumulative(value: Long?) = TokenValueDto(value, "cumulative_actual_api")

private fun ConversationTokenTotals.toDto() = ConversationTokenTotalsDto(
    TokenValueDto(completedTurns.toLong(), "completed_turns"), estimated(currentHistoryTokens),
    cumulative(cumulativeApiInputTokens), cumulative(cumulativeOutputTokens), cumulative(cumulativeReasoningTokens),
    cumulative(cumulativeTotalTokens), cumulative(cumulativeCachedInputTokens), cumulative(cumulativeCacheWriteInputTokens),
    DecimalValueDto(cumulativeCostUsd?.toPlainString(), "cumulative_actual_usage"),
    TokenValueDto(contextTruncations.toLong(), "derived"), scope,
)

private fun TurnTokenMetrics.toDto(): TurnTokenMetricsDto {
    val usage = actualUsage
    return TurnTokenMetricsDto(
        id, turnNumber, model,
        TokenValueDto(estimatedCurrentMessageTokens.tokens.toLong(), "estimate", !estimatedCurrentMessageTokens.exact),
        TokenValueDto(estimatedHistoryTokens.tokens.toLong(), "estimate", !estimatedHistoryTokens.exact),
        TokenValueDto(estimatedContextTokens.tokens.toLong(), "estimate", !estimatedContextTokens.exact),
        actual(usage?.promptTokens), actual(usage?.completionTokens), actual(usage?.reasoningTokens),
        actual(usage?.cachedPromptTokens), actual(usage?.cacheWritePromptTokens), actual(usage?.totalTokens),
        profile(contextBudget.contextWindow), requested(contextBudget.requestedMaxOutputTokens),
        derived(contextBudget.reservedOutputTokens), derived(contextBudget.availableInputTokens),
        DecimalValueDto(contextBudget.estimatedContextUsagePercent?.toPlainString(), "derived_estimate", true),
        derived(contextBudget.estimatedRemainingInputTokens), finishReason,
        DecimalValueDto(turnCostUsd?.toPlainString(), "actual_usage_and_pricing"), cumulativeTotals.toDto(),
        overflowPolicy.name, TokenValueDto(excludedMessageCount.toLong(), "context_policy"),
        estimated(excludedEstimatedTokens), estimated(requiredTokens), estimated(exceededByTokens),
        estimatedContextTokens.estimatorId, estimatedContextTokens.warning, pricingProfileId,
        pricingEffectiveDate?.toString(), pricingSourceUrl, contextProfileSimulated, createdAtEpochMillis,
    )
}

private fun LlmMessage.toHistoryDto() = HistoryMessageDto(role.apiValue, content)
private fun ContextDiagnostics.toDto() = ContextDto(
    strategy.name,
    recentMessagesLimit,
    facts,
    FactUsageDto(factUsage.requests, factUsage.inputTokens, factUsage.outputTokens, factUsage.totalTokens),
    checkpoint?.let { CheckpointDto(it.id, it.createdAtEpochMillis, it.messages.map(LlmMessage::toHistoryDto)) },
    branches.map { ContextBranchDto(it.id, it.name, it.messages.map(LlmMessage::toHistoryDto)) },
    activeBranchId,
)

fun WorkbenchState.toDto(): StateDto = StateDto(
    revision, settingsVersion, settings.toDto(),
    providers = LlmKind.entries.map { kind -> ProviderDto(kind.name, kind.displayName(), kind in configuredProviders,
        (LlmModels.availableFor(kind) + if (kind == settings.llmKind) listOf(LlmModel(settings.model)) else emptyList())
            .distinctBy { it.id }.map { ModelDto(it.id, it.displayName) }) },
    modes = modes,
    history = HistoryDto(historyTurnCounts[ResponseVariant.UNRESTRICTED] ?: 0, historyTurnCounts[ResponseVariant.CONTROLLED] ?: 0),
    exchanges = exchanges.map { exchange ->
        val report = ((exchange.outcome as? ExchangeOutcome.Completed)?.result as? RequestResult.ModelComparison)?.report
        val tokenReport = ((exchange.outcome as? ExchangeOutcome.Completed)?.result as? RequestResult.TokensContext)?.report
        ExchangeDto(exchange.id, exchange.prompt, exchange.mode.cliValue,
            when (exchange.outcome) { ExchangeOutcome.Pending -> "pending"; ExchangeOutcome.Cancelled -> "cancelled"; is ExchangeOutcome.Failed -> "failed"; is ExchangeOutcome.Completed -> "completed" },
            (exchange.outcome as? ExchangeOutcome.Failed)?.message,
            exchange.outputs.map { output ->
                val completion = output.completion
                val text = completion?.content
                val usage = completion?.usage
                val metrics = completion?.takeUnless { output.streaming }?.metrics(output.title)
                OutputDto(output.id, output.title, output.kind, text, output.error, completion?.model,
                    MetricsDto(metrics?.characterCount, metrics?.wordCount,
                        metrics?.completionTokens, metrics?.finishReason, usage?.promptTokens, usage?.reasoningTokens,
                        usage?.totalTokens, output.elapsedMillis, output.estimatedCostUsd,
                        usage?.cachedPromptTokens, usage?.cacheWritePromptTokens), output.streaming,
                    output.tokenMetrics?.toDto(), output.contextStrategy?.name, output.branchId, output.branchName)
            }, report?.estimatedTotalCostUsd,
            if (report != null && report.evaluation == null) "Автооценка пропущена: нужны хотя бы два успешных ответа." else null,
            (exchange.outcome as? ExchangeOutcome.Failed)?.code,
            tokenReport?.sentinelInPermanentHistory, tokenReport?.sentinelInActiveContext)
    },
    operation = (operation as? OperationState.Running)?.let { running ->
        OperationDto(exchanges.last().id, ProgressDto(running.progress.current, running.progress.total, running.progress.label))
    },
    notice = notice?.let { NoticeDto(it.kind.name.lowercase(), it.message) },
    tokenConversations = ResponseVariant.entries.associate { variant ->
        variant.name.lowercase() to TokenConversationDto(
            tokenMetrics[variant].orEmpty().map { it.toDto() },
            (tokenTotals[variant] ?: ConversationTokenTotals()).toDto(),
        )
    },
    context = context.toDto(),
)
