package org.example.llm

interface LlmClient {
    suspend fun complete(
        messages: List<LlmMessage>,
        options: CompletionOptions = CompletionOptions(),
    ): CompletionResult

    suspend fun complete(
        prompt: String,
        options: CompletionOptions = CompletionOptions(),
    ): CompletionResult = complete(
        messages = listOf(LlmMessage(LlmRole.USER, prompt)),
        options = options,
    )
}

data class CompletionResult(
    val content: String,
    val finishReason: String?,
    val usage: TokenUsage?,
    val model: String? = null,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedPromptTokens: Int = 0,
    val cacheWritePromptTokens: Int = 0,
    val reasoningTokens: Int = 0,
)

enum class LlmRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

data class CompletionOptions(
    val maxTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val temperature: Double? = null,
    val reasoningEffort: ReasoningEffort? = null,
) {
    init {
        require(maxTokens == null || maxTokens > 0) {
            "maxTokens должен быть больше нуля"
        }
        require(stopSequences.none(String::isBlank)) {
            "stop sequence не может быть пустой"
        }
        require(temperature == null || temperature.isFinite() && temperature in 0.0..2.0) {
            "temperature должна быть числом от 0 до 2"
        }
    }
}

enum class ReasoningEffort(val apiValue: String) {
    MEDIUM("medium"),
}

class LlmApiException(message: String) : RuntimeException(message)
