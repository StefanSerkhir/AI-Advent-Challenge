package org.example.llm

interface LlmClient {
    suspend fun complete(prompt: String): String
}

class LlmApiException(message: String) : RuntimeException(message)
