package org.example.llm.ollama

import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import org.example.llm.*
import org.example.llm.openai.OpenAiCompatibleConfig
import org.example.llm.openai.OpenAiCompatibleLlmClient
import java.io.IOException

const val OLLAMA_CHAT_COMPLETIONS_URL = "http://127.0.0.1:11434/v1/chat/completions"
const val DEFAULT_OLLAMA_MODEL = "qwen3:14b"

class OllamaLlmClient(
    httpClient: HttpClient,
    model: String = DEFAULT_OLLAMA_MODEL,
) : LlmClient {
    private val delegate = OpenAiCompatibleLlmClient(
        apiKey = null,
        httpClient = httpClient,
        config = OpenAiCompatibleConfig(
            chatCompletionsUrl = OLLAMA_CHAT_COMPLETIONS_URL,
            model = model,
            supportsReasoningEffort = true,
            defaultReasoningEffort = ReasoningEffort.NONE,
        ),
    )

    override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult =
        try {
            delegate.complete(messages, options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            throw unavailable(error)
        }

    override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> =
        delegate.stream(messages, options).catch { error ->
            when (error) {
                is CancellationException -> throw error
                is IOException -> throw unavailable(error)
                else -> throw error
            }
        }

    private fun unavailable(cause: IOException) = LlmApiException(
        "Ollama недоступна на 127.0.0.1:11434. Запустите локальный сервис и проверьте модель qwen3:14b.",
    ).also { it.initCause(cause) }
}
