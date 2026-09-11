package org.example.app

import org.example.agent.ContextStrategy
import org.example.llm.CompletionResult
import org.example.tokens.TurnTokenMetrics

/** Completed stages remain available even when a later stage fails or is cancelled. */
data class ExperimentOutput(
    val id: String,
    val title: String,
    val completion: CompletionResult? = null,
    val error: String? = null,
    val kind: String = "response",
    val elapsedMillis: Long? = null,
    val estimatedCostUsd: Double? = null,
    val streaming: Boolean = false,
    val tokenMetrics: TurnTokenMetrics? = null,
    val contextStrategy: ContextStrategy? = null,
    val branchId: String? = null,
    val branchName: String? = null,
)

data class ExperimentOutputDelta(
    val id: String,
    val title: String,
    val content: String,
    val kind: String = "response",
    val tokenMetrics: TurnTokenMetrics? = null,
)

fun ExperimentOutputDelta.asOutput() = ExperimentOutput(
    id = id,
    title = title,
    completion = CompletionResult(content, finishReason = null, usage = null),
    kind = kind,
    streaming = true,
    tokenMetrics = tokenMetrics,
)

fun ModelComparisonRun.asOutput(evaluation: Boolean = false) = ExperimentOutput(
    id = if (evaluation) "evaluation" else target.answerLabel,
    title = if (evaluation) "Слепая автооценка · GPT-5.6 Sol" else "${target.answerLabel} · ${target.displayName} · ${target.tierLabel}",
    completion = completion,
    error = (outcome as? ModelCallOutcome.Failure)?.message,
    kind = if (evaluation) "evaluation" else "response",
    elapsedMillis = elapsedMillis,
    estimatedCostUsd = estimatedCostUsd,
)

fun validateSettings(settings: AppSettings) {
    require(settings.maxTokens > 0 && settings.maxWords > 0 && settings.bulletCount > 0 &&
        settings.recentMessagesLimit > 0) {
        "Лимиты и параметры управления контекстом должны быть целыми числами больше нуля."
    }
    require(settings.model.isNotBlank() && settings.model.length <= 200 && settings.model.none { it.isISOControl() }) {
        "Укажите корректную модель."
    }
    require(settings.stopSequence == null || settings.stopSequence!!.let {
        it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl) && !it.equals("off", ignoreCase = true)
    }) { "Stop sequence должна быть непустой строкой до 4096 символов; off зарезервировано для отключения." }
}
