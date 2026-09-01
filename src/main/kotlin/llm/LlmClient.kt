package org.example.llm

interface LlmClient {
    suspend fun complete(
        prompt: String,
        options: CompletionOptions = CompletionOptions(),
    ): String
}

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
