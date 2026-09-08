package org.example.llm.openai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private val streamJson = Json { ignoreUnknownKeys = true }

    override suspend fun complete(
        messages: List<LlmMessage>,
        options: CompletionOptions,
    ): CompletionResult {
        val httpResponse = httpClient.post(config.chatCompletionsUrl) {
            configureRequest(messages, options, streaming = false)
        }

        ensureSuccessful(httpResponse.status)

        val response = httpResponse.body<ChatCompletionResponse>()
        val choice = response.choices.firstOrNull()
            ?: throw LlmApiException("провайдер вернул пустой ответ")
        return CompletionResult(
            content = choice.message.content ?: throw LlmApiException("провайдер вернул ответ без текста"),
            finishReason = choice.finishReason,
            usage = response.usage?.toTokenUsage(),
            model = response.model,
        )
    }

    override fun stream(
        messages: List<LlmMessage>,
        options: CompletionOptions,
    ): Flow<CompletionEvent> = flow {
        httpClient.preparePost(config.chatCompletionsUrl) {
            configureRequest(messages, options, streaming = true)
        }.execute { httpResponse ->
            ensureSuccessful(httpResponse.status)

            val dataLines = mutableListOf<String>()
            var receivedDone = false
            var finishReason: String? = null
            var usage: TokenUsage? = null
            var model: String? = null

            suspend fun consumeEvent(): Boolean {
                if (dataLines.isEmpty()) return false
                val data = dataLines.joinToString("\n")
                dataLines.clear()
                if (data.isBlank()) return false
                if (data.trim() == "[DONE]") return true

                val element = streamJson.parseToJsonElement(data)
                element.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.let {
                    throw LlmApiException("провайдер прервал поток: $it")
                }
                val chunk = streamJson.decodeFromJsonElement<ChatCompletionChunk>(element)
                model = chunk.model ?: model
                usage = chunk.usage?.toTokenUsage() ?: usage
                chunk.choices.forEach { choice ->
                    finishReason = choice.finishReason ?: finishReason
                    choice.delta.content?.takeIf(String::isNotEmpty)?.let { emit(TextDelta(it)) }
                }
                return false
            }

            val channel = httpResponse.bodyAsChannel()
            while (!receivedDone) {
                val line = channel.readLine() ?: break
                when {
                    line.isEmpty() -> receivedDone = consumeEvent()
                    line.startsWith(":") -> Unit
                    line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
                }
            }
            if (!receivedDone) receivedDone = consumeEvent()
            if (!receivedDone) {
                throw LlmApiException("потоковый ответ провайдера оборвался до завершения")
            }
            emit(CompletionFinished(finishReason, usage, model))
        }
    }

    private fun HttpRequestBuilder.configureRequest(
        messages: List<LlmMessage>,
        options: CompletionOptions,
        streaming: Boolean,
    ) {
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
                stream = true.takeIf { streaming },
                streamOptions = ChatCompletionStreamOptions(includeUsage = true).takeIf { streaming },
            ),
        )
    }

    private fun ensureSuccessful(status: HttpStatusCode) {
        if (status.isSuccess()) return
        val message = when (status.value) {
            401 -> "неверный API-ключ"
            402 -> "недостаточно средств на балансе LLM-провайдера"
            403 -> "доступ к выбранной модели запрещён для этого API-ключа"
            404 -> "выбранная модель или API endpoint не найдены"
            408 -> "провайдер не успел обработать запрос"
            409 -> "провайдер сообщил о конфликте запроса; попробуйте ещё раз"
            429 -> "превышен лимит запросов; подождите и повторите попытку"
            in 500..599 -> "сервис LLM временно недоступен (${status.value})"
            else -> "LLM API вернул ошибку ${status.value}"
        }
        throw LlmApiException(message)
    }

    private fun ChatTokenUsage.toTokenUsage() = TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedPromptTokens = promptTokensDetails?.cachedTokens ?: 0,
        cacheWritePromptTokens = promptTokensDetails?.cacheWriteTokens ?: 0,
        reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
    )
}
