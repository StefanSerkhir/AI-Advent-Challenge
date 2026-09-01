package org.example.llm

interface LlmClient {
    suspend fun complete(
        messages: List<LlmMessage>,
        options: CompletionOptions = CompletionOptions(),
    ): String

    suspend fun complete(
        prompt: String,
        options: CompletionOptions = CompletionOptions(),
    ): String = complete(
        messages = listOf(LlmMessage(LlmRole.USER, prompt)),
        options = options,
    )
}

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
