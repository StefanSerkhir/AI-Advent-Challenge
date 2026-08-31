package org.example.llm

import io.ktor.client.HttpClient
import org.example.llm.deepseek.DeepSeekLlmClient

enum class LlmKind {
    DEEPSEEK;

    companion object {
        fun from(value: String): LlmKind = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "неизвестный llm_kind '$value'; доступно: Deepseek",
        )
    }
}

fun createLlmClient(
    kind: LlmKind,
    apiKey: String,
    httpClient: HttpClient,
): LlmClient = when (kind) {
    LlmKind.DEEPSEEK -> DeepSeekLlmClient(apiKey, httpClient)
}
