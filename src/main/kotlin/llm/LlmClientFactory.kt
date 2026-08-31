package org.example.llm

import io.ktor.client.HttpClient
import org.example.llm.deepseek.DeepSeekLlmClient
import org.example.llm.openai.OpenAiLlmClient

enum class LlmKind {
    DEEPSEEK,
    OPENAI;

    companion object {
        fun from(value: String): LlmKind {
            if (value.equals("ChatGPT", ignoreCase = true)) return OPENAI

            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException(
                "неизвестный llm_kind '$value'; доступно: Deepseek, OpenAI",
            )
        }
    }
}

fun createLlmClient(
    kind: LlmKind,
    apiKey: String,
    httpClient: HttpClient,
): LlmClient = when (kind) {
    LlmKind.DEEPSEEK -> DeepSeekLlmClient(apiKey, httpClient)
    LlmKind.OPENAI -> OpenAiLlmClient(apiKey, httpClient)
}
