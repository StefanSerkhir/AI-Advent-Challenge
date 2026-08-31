package org.example.llm.deepseek

import io.ktor.client.HttpClient
import org.example.llm.LlmClient
import org.example.llm.openai.OpenAiCompatibleConfig
import org.example.llm.openai.OpenAiCompatibleLlmClient

class DeepSeekLlmClient(
    apiKey: String,
    httpClient: HttpClient,
) : LlmClient by OpenAiCompatibleLlmClient(
    apiKey = apiKey,
    httpClient = httpClient,
    config = OpenAiCompatibleConfig(
        chatCompletionsUrl = "https://api.deepseek.com/chat/completions",
        model = "deepseek-v4-flash",
    ),
)
