package org.example.llm

import io.ktor.client.*
import org.example.llm.deepseek.DeepSeekLlmClient
import org.example.llm.ollama.OllamaLlmClient
import org.example.llm.openai.OpenAiLlmClient

enum class LlmKind {
    DEEPSEEK,
    OPENAI,
    OLLAMA;

    val requiresApiKey: Boolean
        get() = this != OLLAMA

    companion object {
        fun from(value: String): LlmKind {
            if (value.equals("ChatGPT", ignoreCase = true)) return OPENAI

            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException(
                "неизвестный llm_kind '$value'; доступно: Deepseek, OpenAI, Ollama",
            )
        }
    }
}

fun createLlmClient(
    kind: LlmKind,
    apiKey: String?,
    httpClient: HttpClient,
    model: String = LlmModels.defaultFor(kind),
): LlmClient = when (kind) {
    LlmKind.DEEPSEEK -> DeepSeekLlmClient(requireNotNull(apiKey), httpClient, model)
    LlmKind.OPENAI -> OpenAiLlmClient(requireNotNull(apiKey), httpClient, model)
    LlmKind.OLLAMA -> OllamaLlmClient(httpClient, model)
}
