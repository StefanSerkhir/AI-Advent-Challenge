package org.example.llm.openai

import io.ktor.client.HttpClient
import org.example.llm.LlmClient

class OpenAiLlmClient(
    apiKey: String,
    httpClient: HttpClient,
) : LlmClient by OpenAiCompatibleLlmClient(
    apiKey = apiKey,
    httpClient = httpClient,
    config = OpenAiCompatibleConfig(
        chatCompletionsUrl = "https://api.openai.com/v1/chat/completions",
        model = "gpt-5.6-luna",
    ),
)
