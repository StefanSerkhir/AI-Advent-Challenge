package org.example.indexing

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

data class EmbeddingVector(val values: List<Float>)

interface EmbeddingClient {
    val provider: String
    val model: String
    suspend fun embed(texts: List<String>): List<EmbeddingVector>
}

class EmbeddingApiException(message: String, val statusCode: Int? = null, cause: Throwable? = null) :
    IOException(message, cause)

class OpenAiEmbeddingClient(
    private val apiKey: String,
    private val httpClient: HttpClient,
    override val model: String = DEFAULT_EMBEDDING_MODEL,
    private val endpoint: String = "https://api.openai.com/v1/embeddings",
    private val maxRetries: Int = 2,
    private val retryDelayMillis: suspend (Int) -> Unit = { attempt -> delay(250L shl attempt) },
) : EmbeddingClient {
    override val provider: String = "openai"
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(apiKey.isNotBlank()) { "Для OpenAI embeddings требуется openai_api_key" }
        require(model.isNotBlank()) { "Embedding model не может быть пустой" }
        require(maxRetries in 0..5) { "maxRetries должен быть от 0 до 5" }
    }

    override suspend fun embed(texts: List<String>): List<EmbeddingVector> {
        require(texts.isNotEmpty()) { "Embedding batch не может быть пустым" }
        require(texts.none(String::isBlank)) { "Embedding input не может быть пустым" }

        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val response = httpClient.post(endpoint) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(EmbeddingRequest(model, texts)))
                }
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    val safeMessage = providerError(body)
                    if (isTransient(response.status.value) && attempt < maxRetries) {
                        retryDelayMillis(attempt++)
                        continue
                    }
                    throw EmbeddingApiException(httpErrorMessage(response.status.value, safeMessage), response.status.value)
                }
                return validateResponse(json.decodeFromString<EmbeddingResponse>(body), texts.size)
            } catch (error: CancellationException) {
                throw error
            } catch (error: EmbeddingApiException) {
                throw error
            } catch (error: IOException) {
                if (attempt >= maxRetries) {
                    throw EmbeddingApiException("Не удалось получить embeddings: временная сетевая ошибка", cause = error)
                }
                retryDelayMillis(attempt++)
            }
        }
    }

    private fun validateResponse(response: EmbeddingResponse, expectedCount: Int): List<EmbeddingVector> {
        require(response.data.size == expectedCount) {
            "OpenAI вернул ${response.data.size} embeddings вместо $expectedCount"
        }
        val byIndex = response.data.associateBy(EmbeddingData::index)
        require(byIndex.size == expectedCount && (0 until expectedCount).all(byIndex::containsKey)) {
            "OpenAI вернул неполные или повторяющиеся embedding index"
        }
        val dimensions = byIndex.getValue(0).embedding.size
        require(dimensions > 0) { "OpenAI вернул пустой embedding vector" }
        return (0 until expectedCount).map { index ->
            val values = byIndex.getValue(index).embedding
            require(values.size == dimensions) { "OpenAI вернул embeddings разной размерности" }
            require(values.all(Float::isFinite)) { "OpenAI вернул нечисловое значение embedding" }
            EmbeddingVector(values)
        }
    }

    private fun providerError(body: String): String {
        val message = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: body
        return message
            .replace(apiKey, "<redacted>")
            .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._-]+"), "Bearer <redacted>")
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "<redacted>")
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .take(2_000)
    }

    private fun httpErrorMessage(status: Int, providerMessage: String): String = when (status) {
        401 -> "OpenAI отклонил ключ API"
        403 -> "OpenAI запретил доступ к embedding model"
        404 -> "OpenAI embedding endpoint или model не найдены"
        408 -> "OpenAI не успел обработать embedding batch"
        429 -> "Превышен лимит OpenAI embeddings; повторите позже"
        in 500..599 -> "OpenAI embeddings временно недоступны ($status)"
        else -> providerMessage.takeIf(String::isNotBlank)
            ?.let { "OpenAI отклонил embedding batch ($status): $it" }
            ?: "OpenAI embeddings вернул ошибку $status"
    }

    private fun isTransient(status: Int): Boolean = status in setOf(408, 409, 429) || status >= 500
}

@Serializable
private data class EmbeddingRequest(val model: String, val input: List<String>)

@Serializable
private data class EmbeddingResponse(val data: List<EmbeddingData>)

@Serializable
private data class EmbeddingData(
    val index: Int,
    val embedding: List<Float>,
    @SerialName("object") val objectType: String? = null,
)
