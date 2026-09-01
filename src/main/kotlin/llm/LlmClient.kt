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
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
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
) {
    init {
        require(maxTokens == null || maxTokens > 0) {
            "maxTokens должен быть больше нуля"
        }
        require(stopSequences.none(String::isBlank)) {
            "stop sequence не может быть пустой"
        }
    }
}

class LlmApiException(message: String) : RuntimeException(message)
