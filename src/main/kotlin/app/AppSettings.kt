package org.example.app

import org.example.llm.LlmKind
import org.example.llm.LlmModels
import org.example.tokens.ContextOverflowPolicy

const val DEFAULT_STOP_SEQUENCE = "<END_OF_RESPONSE>"

enum class ResponseMode(val cliValue: String) {
    COMPARE("compare"),
    CONTROLLED("controlled"),
    UNRESTRICTED("unrestricted"),
    REASONING("reasoning"),
    TEMPERATURE("temperature"),
    MODEL_COMPARISON("models"),
    TOKENS_CONTEXT("tokens");

    companion object {
        fun from(value: String): ResponseMode? = entries.firstOrNull {
            it.cliValue.equals(value, ignoreCase = true)
        }
    }
}

enum class ResponseVariant(
    val heading: String,
    val tableLabel: String,
) {
    UNRESTRICTED("БЕЗ ОГРАНИЧЕНИЙ", "без ограничений"),
    CONTROLLED("С ОГРАНИЧЕНИЯМИ", "с ограничениями"),
}

data class AppSettings(
    var llmKind: LlmKind,
    var model: String = LlmModels.defaultFor(llmKind),
    var responseMode: ResponseMode = ResponseMode.COMPARE,
    var maxTokens: Int = 300,
    var maxWords: Int = 60,
    var bulletCount: Int = 3,
    var stopSequence: String? = DEFAULT_STOP_SEQUENCE,
    var historyEnabled: Boolean = true,
    var contextOverflowPolicy: ContextOverflowPolicy = ContextOverflowPolicy.REJECT,
) {
    init {
        require(maxTokens > 0)
        require(maxWords > 0)
        require(bulletCount > 0)
        require(model.isNotBlank())
    }
}

fun withResponseConstraints(prompt: String, settings: AppSettings): String {
    val stopInstruction = settings.stopSequence?.let {
        "После последнего пункта напиши $it и сразу заверши ответ."
    } ?: "Сразу после последнего пункта заверши ответ."

    return """
        $prompt

        Требования к ответу:
        - Формат: маркированный список, количество пунктов: ${settings.bulletCount}; каждый пункт начинай с «- ».
        - Длина: не более ${settings.maxWords} слов во всём ответе.
        - Не добавляй заголовок, вступление или заключение.
        - $stopInstruction
    """.trimIndent()
}
