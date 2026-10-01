package org.example.app

import org.example.agent.ContextStrategy
import org.example.agent.DEFAULT_RECENT_MESSAGES_LIMIT
import org.example.llm.LlmKind
import org.example.llm.LlmModels
import org.example.tokens.ContextOverflowPolicy

const val DEFAULT_STOP_SEQUENCE = "<END_OF_RESPONSE>"
const val DEFAULT_RAG_CANDIDATE_LIMIT = 10
const val DEFAULT_RAG_RESULT_LIMIT = 5
const val DEFAULT_RAG_MIN_SIMILARITY = 0.20
const val MAX_RAG_CANDIDATE_LIMIT = 50
const val MAX_RAG_RESULT_LIMIT = 20

enum class ResponseMode(val cliValue: String) {
    COMPARE("compare"),
    CONTROLLED("controlled"),
    UNRESTRICTED("unrestricted"),
    REASONING("reasoning"),
    TEMPERATURE("temperature"),
    MODEL_COMPARISON("models"),
    RAG_COMPARISON("rag"),
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
    var contextStrategy: ContextStrategy = ContextStrategy.SLIDING_WINDOW,
    var recentMessagesLimit: Int = DEFAULT_RECENT_MESSAGES_LIMIT,
    var ragCandidateLimit: Int = DEFAULT_RAG_CANDIDATE_LIMIT,
    var ragResultLimit: Int = DEFAULT_RAG_RESULT_LIMIT,
    var ragMinSimilarity: Double = DEFAULT_RAG_MIN_SIMILARITY,
) {
    init {
        require(maxTokens > 0)
        require(maxWords > 0)
        require(bulletCount > 0)
        require(recentMessagesLimit > 0)
        require(ragCandidateLimit in 1..MAX_RAG_CANDIDATE_LIMIT)
        require(ragResultLimit in 1..MAX_RAG_RESULT_LIMIT && ragResultLimit <= ragCandidateLimit)
        require(ragMinSimilarity.isFinite() && ragMinSimilarity in -1.0..1.0)
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
