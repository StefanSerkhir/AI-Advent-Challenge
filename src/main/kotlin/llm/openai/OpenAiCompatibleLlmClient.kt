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
    val supportsJsonSchema: Boolean = false,
    val supportsTools: Boolean = false,
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

        ensureSuccessful(httpResponse)

        val response = httpResponse.body<ChatCompletionResponse>()
        val choice = response.choices.firstOrNull()
            ?: throw LlmApiException("провайдер вернул пустой ответ")
        val toolCalls = choice.message.toolCalls.orEmpty().map { it.toDomain() }
        val content = choice.message.content.orEmpty()
        if (content.isEmpty() && toolCalls.isEmpty()) throw LlmApiException("провайдер вернул ответ без текста или вызова инструмента")
        return CompletionResult(
            content = content,
            finishReason = choice.finishReason,
            usage = response.usage?.toTokenUsage(),
            model = response.model,
            toolCalls = toolCalls,
        )
    }

    override fun stream(
        messages: List<LlmMessage>,
        options: CompletionOptions,
    ): Flow<CompletionEvent> = flow {
        httpClient.preparePost(config.chatCompletionsUrl) {
            configureRequest(messages, options, streaming = true)
        }.execute { httpResponse ->
            ensureSuccessful(httpResponse)

            val dataLines = mutableListOf<String>()
            var receivedDone = false
            var finishReason: String? = null
            var usage: TokenUsage? = null
            var model: String? = null
            val toolCalls = sortedMapOf<Int, StreamingToolCall>()

            suspend fun consumeEvent(): Boolean {
                if (dataLines.isEmpty()) return false
                val data = dataLines.joinToString("\n")
                dataLines.clear()
                if (data.isBlank()) return false
                if (data.trim() == "[DONE]") return true

                val element = streamJson.parseToJsonElement(data)
                element.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.let {
                    if (isContextError(it)) throw LlmContextApiException(
                        "Провайдер прервал поток из-за превышения контекстного окна. Уменьшите историю или резерв ответа.",
                    )
                    throw LlmApiException("провайдер прервал поток: $it")
                }
                val chunk = streamJson.decodeFromJsonElement<ChatCompletionChunk>(element)
                model = chunk.model ?: model
                usage = chunk.usage?.toTokenUsage() ?: usage
                chunk.choices.forEach { choice ->
                    finishReason = choice.finishReason ?: finishReason
                    choice.delta.content?.takeIf(String::isNotEmpty)?.let { emit(TextDelta(it)) }
                    choice.delta.toolCalls.forEach { delta ->
                        val current = toolCalls.getOrPut(delta.index) { StreamingToolCall() }
                        delta.id?.let { current.id = it }
                        delta.function?.name?.let(current.name::append)
                        delta.function?.arguments?.let(current.arguments::append)
                    }
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
            emit(CompletionFinished(finishReason, usage, model, toolCalls.values.map(StreamingToolCall::toDomain)))
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
                messages = messages.map { message ->
                    ChatMessage(
                        role = message.role.apiValue,
                        content = message.content.takeUnless { it.isEmpty() && message.toolCalls.isNotEmpty() },
                        toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
                            ChatToolCall(
                                id = call.id,
                                type = "function",
                                function = ChatToolCallFunction(call.name, call.arguments),
                            )
                        },
                        toolCallId = message.toolCallId,
                        name = message.name.takeIf { message.role != LlmRole.TOOL },
                    )
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
                responseFormat = options.structuredOutput
                    ?.takeIf { config.supportsJsonSchema }
                    ?.let { structured ->
                        ChatCompletionResponseFormat(
                            type = "json_schema",
                            jsonSchema = ChatCompletionJsonSchema(
                                name = structured.name,
                                strict = true,
                                schema = structured.schema,
                            ),
                        )
                    },
                tools = options.tools
                    .takeIf { config.supportsTools && it.isNotEmpty() }
                    ?.map { tool ->
                        ChatTool(
                            type = "function",
                            function = ChatFunctionDefinition(
                                name = tool.name,
                                description = tool.description,
                                parameters = tool.inputSchema,
                            ),
                        )
                    },
            ),
        )
    }

    private suspend fun ensureSuccessful(response: HttpResponse) {
        val status = response.status
        if (status.isSuccess()) return
        val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
        val providerMessage = runCatching {
            val root = streamJson.parseToJsonElement(errorBody).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: errorBody
        val safeProviderMessage = providerMessage
            .replace(apiKey, "<redacted>")
            .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._-]+"), "Bearer <redacted>")
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "<redacted>")
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .take(2_000)
        if (status.value == 400 && isContextError(providerMessage)) {
            throw LlmContextApiException(
                "Провайдер отклонил запрос из-за превышения контекстного окна. Уменьшите историю или резерв ответа.",
                status.value,
            )
        }
        val message = when (status.value) {
            401 -> "неверный API-ключ"
            402 -> "недостаточно средств на балансе LLM-провайдера"
            403 -> "доступ к выбранной модели запрещён для этого API-ключа"
            404 -> "выбранная модель или API endpoint не найдены"
            408 -> "провайдер не успел обработать запрос"
            409 -> "провайдер сообщил о конфликте запроса; попробуйте ещё раз"
            429 -> "превышен лимит запросов; подождите и повторите попытку"
            in 500..599 -> "сервис LLM временно недоступен (${status.value})"
            400 -> safeProviderMessage.takeIf(String::isNotBlank)
                ?.let { "LLM-провайдер отклонил запрос: $it" }
                ?: "LLM API вернул ошибку 400"
            else -> "LLM API вернул ошибку ${status.value}"
        }
        throw LlmApiException(message)
    }

    private fun isContextError(message: String): Boolean {
        val normalized = message.lowercase()
        return listOf("context length", "context window", "maximum context", "too many tokens", "context_length_exceeded")
            .any(normalized::contains)
    }

    private fun ChatTokenUsage.toTokenUsage() = TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedPromptTokens = promptTokensDetails?.cachedTokens ?: 0,
        cacheWritePromptTokens = promptTokensDetails?.cacheWriteTokens ?: 0,
        reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
    )

    private fun ChatToolCall.toDomain() = LlmToolCall(id, function.name, function.arguments)

    private class StreamingToolCall {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()

        fun toDomain(): LlmToolCall {
            if (id.isBlank() || name.isBlank()) {
                throw LlmApiException("провайдер вернул неполный вызов инструмента")
            }
            return LlmToolCall(id, name.toString(), arguments.toString())
        }
    }
}
