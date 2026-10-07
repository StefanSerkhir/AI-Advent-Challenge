package org.example.indexing

import io.ktor.client.*

enum class EmbeddingProvider(val wireName: String, val defaultModel: String) {
    OPENAI("openai", DEFAULT_EMBEDDING_MODEL),
    OLLAMA("ollama", DEFAULT_OLLAMA_EMBEDDING_MODEL);

    companion object {
        fun fromWireName(value: String): EmbeddingProvider = entries.firstOrNull { it.wireName == value.lowercase() }
            ?: throw IllegalArgumentException("Embedding provider должен быть openai или ollama")
    }
}

fun createEmbeddingClient(
    provider: String,
    model: String,
    httpClient: HttpClient,
    openAiApiKey: String? = null,
): EmbeddingClient = when (EmbeddingProvider.fromWireName(provider)) {
    EmbeddingProvider.OPENAI -> OpenAiEmbeddingClient(
        apiKey = openAiApiKey?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Для OpenAI embeddings требуется openai_api_key"),
        httpClient = httpClient,
        model = model,
    )
    EmbeddingProvider.OLLAMA -> OllamaEmbeddingClient(httpClient = httpClient, model = model)
}
