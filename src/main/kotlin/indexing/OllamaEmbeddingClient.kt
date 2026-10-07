package org.example.indexing

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

const val OLLAMA_EMBEDDINGS_URL = "http://127.0.0.1:11434/api/embed"
const val DEFAULT_OLLAMA_EMBEDDING_MODEL = "qwen3-embedding:0.6b"

/** Keyless Ollama embedding transport. The endpoint is intentionally fixed to loopback. */
class OllamaEmbeddingClient(
    private val httpClient: HttpClient,
    override val model: String = DEFAULT_OLLAMA_EMBEDDING_MODEL,
) : EmbeddingClient {
    override val provider: String = "ollama"
    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }

    init {
        require(model.isNotBlank()) { "Embedding model не может быть пустой" }
    }

    override suspend fun embed(texts: List<String>): List<EmbeddingVector> {
        require(texts.isNotEmpty()) { "Embedding batch не может быть пустым" }
        require(texts.none(String::isBlank)) { "Embedding input не может быть пустым" }
        currentCoroutineContext().ensureActive()
        try {
            val response = httpClient.post(OLLAMA_EMBEDDINGS_URL) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(OllamaEmbeddingRequest(model, texts, truncate = false)))
            }
            currentCoroutineContext().ensureActive()
            val status = response.status
            val body = response.bodyAsText()
            if (!status.isSuccess()) throw EmbeddingApiException(httpErrorMessage(status), status.value)
            val decoded = try {
                json.decodeFromString<OllamaEmbeddingResponse>(body)
            } catch (error: SerializationException) {
                throw EmbeddingApiException("Ollama embeddings вернула ответ в неожиданном формате", cause = error)
            }
            return validateResponse(decoded, texts.size)
        } catch (error: CancellationException) {
            throw error
        } catch (error: EmbeddingApiException) {
            throw error
        } catch (error: IOException) {
            throw EmbeddingApiException(
                "Ollama embeddings недоступна на 127.0.0.1:11434. Запустите Ollama и проверьте модель $model.",
                cause = error,
            )
        }
    }

    private fun validateResponse(response: OllamaEmbeddingResponse, expectedCount: Int): List<EmbeddingVector> {
        require(response.embeddings.size == expectedCount) {
            "Ollama вернула ${response.embeddings.size} embeddings вместо $expectedCount"
        }
        val dimensions = response.embeddings.firstOrNull()?.size ?: 0
        require(dimensions > 0) { "Ollama вернула пустой embedding vector" }
        return response.embeddings.map { values ->
            require(values.size == dimensions) { "Ollama вернула embeddings разной размерности" }
            require(values.all(Float::isFinite)) { "Ollama вернула нечисловое значение embedding" }
            EmbeddingVector(values)
        }
    }

    private fun httpErrorMessage(status: HttpStatusCode): String = when (status.value) {
        400 -> "Ollama отклонила embedding batch. Проверьте размер входа; truncate=false запрещает скрытое усечение."
        404 -> "Модель Ollama embeddings '$model' не установлена. Выполните: ollama pull $model"
        408 -> "Ollama не успела обработать embedding batch"
        429 -> "Ollama временно не может обработать embedding batch; повторите позже"
        in 500..599 -> "Ollama embeddings временно недоступна (${status.value})"
        else -> "Ollama embeddings вернула ошибку ${status.value}"
    }
}

@Serializable
private data class OllamaEmbeddingRequest(
    val model: String,
    val input: List<String>,
    val truncate: Boolean,
)

@Serializable
private data class OllamaEmbeddingResponse(
    val model: String? = null,
    val embeddings: List<List<Float>>,
)
