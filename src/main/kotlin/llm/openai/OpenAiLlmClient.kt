package org.example.llm.openai

import io.ktor.client.*
import org.example.llm.LlmClient

class OpenAiLlmClient(
    apiKey: String,
    httpClient: HttpClient,
    model: String = "gpt-5.6-luna",
) : LlmClient by OpenAiCompatibleLlmClient(
    apiKey = apiKey,
    httpClient = httpClient,
    config = OpenAiCompatibleConfig(
        chatCompletionsUrl = "https://api.openai.com/v1/chat/completions",
        model = model,
        temperatureModel = if (model == "gpt-5.6-luna") "gpt-4.1-mini" else model,
        useMaxCompletionTokens = true,
        supportsStopSequences = false,
    ),
)
