package org.example.llm.openai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.example.llm.CompletionResult
import org.example.llm.CompletionOptions
import org.example.llm.LlmApiException
import org.example.llm.LlmClient
import org.example.llm.LlmMessage
import org.example.llm.TokenUsage

data class OpenAiCompatibleConfig(
    val chatCompletionsUrl: String,
    val model: String,
    val useMaxCompletionTokens: Boolean = false,
    val supportsStopSequences: Boolean = true,
)

class OpenAiCompatibleLlmClient(
    private val apiKey: String,
    private val httpClient: HttpClient,
    private val config: OpenAiCompatibleConfig,
) : LlmClient {

    override suspend fun complete(
        messages: List<LlmMessage>,
        options: CompletionOptions,
    ): CompletionResult {
        val httpResponse = httpClient.post(config.chatCompletionsUrl) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(
                ChatCompletionRequest(
                    model = config.model,
                    messages = messages.map {
                        ChatMessage(role = it.role.apiValue, content = it.content)
                    },
                    maxTokens = options.maxTokens.takeUnless {
                        config.useMaxCompletionTokens
                    },
                    maxCompletionTokens = options.maxTokens.takeIf {
                        config.useMaxCompletionTokens
                    },
                    stop = options.stopSequences
                        .takeIf { config.supportsStopSequences && it.isNotEmpty() },
                ),
            )
        }

        if (!httpResponse.status.isSuccess()) {
            val message = when (httpResponse.status.value) {
                401 -> "неверный API-ключ"
                402 -> "недостаточно средств на балансе LLM-провайдера"
                else -> "LLM API error ${httpResponse.status}: ${httpResponse.bodyAsText()}"
            }
            throw LlmApiException(message)
        }

        val response = httpResponse.body<ChatCompletionResponse>()
        val choice = response.choices.firstOrNull()
            ?: error("LLM returned an empty response")
        return CompletionResult(
            content = choice.message.content ?: error("LLM returned empty content"),
            finishReason = choice.finishReason,
            usage = response.usage?.let {
                TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                )
            },
        )
    }
}
