package org.example.llm.openai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.example.llm.*

data class OpenAiCompatibleConfig(
    val chatCompletionsUrl: String,
    val model: String,
    val temperatureModel: String? = null,
    val useMaxCompletionTokens: Boolean = false,
    val supportsStopSequences: Boolean = true,
    val supportsReasoningEffort: Boolean = false,
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
                    model = if (options.temperature == null) {
                        config.model
                    } else {
                        config.temperatureModel ?: config.model
                    },
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
                    temperature = options.temperature,
                    reasoningEffort = options.reasoningEffort
                        ?.apiValue
                        .takeIf { config.supportsReasoningEffort },
                ),
            )
        }

        if (!httpResponse.status.isSuccess()) {
            val message = when (httpResponse.status.value) {
                401 -> "неверный API-ключ"
                402 -> "недостаточно средств на балансе LLM-провайдера"
                403 -> "доступ к выбранной модели запрещён для этого API-ключа"
                404 -> "выбранная модель или API endpoint не найдены"
                408 -> "провайдер не успел обработать запрос"
                409 -> "провайдер сообщил о конфликте запроса; попробуйте ещё раз"
                429 -> "превышен лимит запросов; подождите и повторите попытку"
                in 500..599 -> "сервис LLM временно недоступен (${httpResponse.status.value})"
                else -> "LLM API вернул ошибку ${httpResponse.status.value}"
            }
            throw LlmApiException(message)
        }

        val response = httpResponse.body<ChatCompletionResponse>()
        val choice = response.choices.firstOrNull()
            ?: throw LlmApiException("провайдер вернул пустой ответ")
        return CompletionResult(
            content = choice.message.content ?: throw LlmApiException("провайдер вернул ответ без текста"),
            finishReason = choice.finishReason,
            usage = response.usage?.let {
                TokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                    cachedPromptTokens = it.promptTokensDetails?.cachedTokens ?: 0,
                    cacheWritePromptTokens = it.promptTokensDetails?.cacheWriteTokens ?: 0,
                    reasoningTokens = it.completionTokensDetails?.reasoningTokens ?: 0,
                )
            },
            model = response.model,
        )
    }
}
