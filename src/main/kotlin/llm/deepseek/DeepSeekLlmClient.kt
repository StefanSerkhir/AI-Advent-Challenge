package org.example.llm.deepseek

import io.ktor.client.*
import org.example.llm.LlmClient
import org.example.llm.openai.OpenAiCompatibleConfig
import org.example.llm.openai.OpenAiCompatibleLlmClient

class DeepSeekLlmClient(
    apiKey: String,
    httpClient: HttpClient,
    model: String = "deepseek-v4-flash",
) : LlmClient by OpenAiCompatibleLlmClient(
    apiKey = apiKey,
    httpClient = httpClient,
    config = OpenAiCompatibleConfig(
        chatCompletionsUrl = "https://api.deepseek.com/chat/completions",
        model = model,
    ),
)
