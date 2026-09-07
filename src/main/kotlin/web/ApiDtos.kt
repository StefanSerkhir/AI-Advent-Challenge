package org.example.web

import kotlinx.serialization.Serializable
import org.example.app.*
import org.example.llm.*

@Serializable
data class SettingsDto(
    val provider: String, val model: String, val mode: String,
    val maxTokens: Int, val maxWords: Int, val bulletCount: Int,
    val stopSequence: String?, val historyEnabled: Boolean,
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
)
@Serializable
data class OutputDto(
    val id: String, val title: String, val kind: String, val content: String?,
    val error: String?, val model: String?, val metrics: MetricsDto,
)
@Serializable
data class ExchangeDto(
    val id: Long, val prompt: String, val mode: String, val status: String,
    val error: String?, val outputs: List<OutputDto>, val estimatedTotalCostUsd: Double?, val evaluationNote: String?,
)
@Serializable
data class HistoryMessageDto(val role: String, val content: String)
@Serializable
data class HistoryDetailsDto(val counts: HistoryDto, val branches: Map<String, List<HistoryMessageDto>>)
@Serializable
data class HistoryDto(val unrestricted: Int, val controlled: Int)
@Serializable
data class StateDto(
    val revision: Long, val settingsVersion: Long, val settings: SettingsDto,
    val providers: List<ProviderDto>, val modes: List<ModeDto>, val history: HistoryDto,
    val exchanges: List<ExchangeDto>, val operation: OperationDto?, val notice: NoticeDto?,
    val priceDate: String = MODEL_PRICE_DATE,
)

fun AppSettings.toDto() = SettingsDto(llmKind.name, model, responseMode.cliValue, maxTokens, maxWords, bulletCount, stopSequence, historyEnabled)

fun SettingsDto.toSettings(): AppSettings {
    val kind = LlmKind.entries.firstOrNull { it.name == provider }
        ?: throw ApiProblem(400, "validation", "Неизвестный провайдер.")
    val responseMode = ResponseMode.from(mode)
        ?: throw ApiProblem(400, "validation", "Неизвестный режим.")
    if (maxTokens <= 0 || maxWords <= 0 || bulletCount <= 0) throw ApiProblem(400, "validation", "Лимиты должны быть целыми числами больше нуля.")
    return AppSettings(kind, model, responseMode, maxTokens, maxWords, bulletCount, stopSequence, historyEnabled).also(::validateSettings)
}

val modes = listOf(
    ModeDto("compare", "Сравнение ответов", "Два ответа: свободный и с вашими ограничениями. Для каждого сохраняется отдельная ветка истории.", usesTokenLimit = true, usesTextConstraints = true),
    ModeDto("controlled", "С ограничениями", "Маркированный список с лимитом слов, пунктов, токенов и stop sequence.", usesTokenLimit = true, usesTextConstraints = true),
    ModeDto("unrestricted", "Без ограничений", "Обычный диалог без дополнительных ограничений ответа."),
    ModeDto("reasoning", "4 способа рассуждения", "Прямой ответ, пошаговое решение, созданный промпт и группа экспертов. Затем — оценка точности. 6 вызовов, независимые контексты.", independentContext = true),
    ModeDto("temperature", "Сравнение температуры", "Temperature 0, 0.7 и 1.2, затем оценка точности, креативности и разнообразия. Для Luna используется gpt-4.1-mini. 4 независимых вызова.", independentContext = true),
    ModeDto("models", "Сравнение моделей GPT-5.6", "Luna, Terra и Sol последовательно отвечают на один запрос с reasoning_effort=medium. Sol оценивает анонимные ответы A/B/C. Нужен ключ OpenAI.", independentContext = true, connectionLocked = true, usesTokenLimit = true),
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
        ExchangeDto(exchange.id, exchange.prompt, exchange.mode.cliValue,
            when (exchange.outcome) { ExchangeOutcome.Pending -> "pending"; ExchangeOutcome.Cancelled -> "cancelled"; is ExchangeOutcome.Failed -> "failed"; is ExchangeOutcome.Completed -> "completed" },
            (exchange.outcome as? ExchangeOutcome.Failed)?.message,
            exchange.outputs.map { output ->
                val completion = output.completion
                val text = completion?.content
                val usage = completion?.usage
                val metrics = completion?.metrics(output.title)
                OutputDto(output.id, output.title, output.kind, text, output.error, completion?.model,
                    MetricsDto(metrics?.characterCount, metrics?.wordCount,
                        metrics?.completionTokens, metrics?.finishReason, usage?.promptTokens, usage?.reasoningTokens,
                        usage?.totalTokens, output.elapsedMillis, output.estimatedCostUsd))
            }, report?.estimatedTotalCostUsd,
            if (report != null && report.evaluation == null) "Автооценка пропущена: нужны хотя бы два успешных ответа." else null)
    },
    operation = (operation as? OperationState.Running)?.let { running ->
        OperationDto(exchanges.last().id, ProgressDto(running.progress.current, running.progress.total, running.progress.label))
    },
    notice = notice?.let { NoticeDto(it.kind.name.lowercase(), it.message) },
)
