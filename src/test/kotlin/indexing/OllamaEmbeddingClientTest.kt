package org.example.indexing

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.test.*

class OllamaEmbeddingClientTest {
    @Test
    fun `uses fixed batch endpoint truncate false no authorization and preserves vector order`() = runBlocking {
        var request: HttpRequestData? = null
        val client = HttpClient(MockEngine { captured ->
            request = captured
            respond(
                """{"model":"qwen3-embedding:0.6b","embeddings":[[1.0,0.0],[0.0,1.0]]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        try {
            val vectors = OllamaEmbeddingClient(client).embed(listOf("first", "second"))
            val captured = requireNotNull(request)
            assertEquals(OLLAMA_EMBEDDINGS_URL, captured.url.toString())
            assertFalse(captured.headers.contains(HttpHeaders.Authorization))
            val body = Json.parseToJsonElement((captured.body as TextContent).text).jsonObject
            assertEquals(DEFAULT_OLLAMA_EMBEDDING_MODEL, body.getValue("model").jsonPrimitive.content)
            assertEquals(listOf("first", "second"), body.getValue("input").jsonArray.map { it.jsonPrimitive.content })
            assertFalse(body.getValue("truncate").jsonPrimitive.boolean)
            assertEquals(listOf(1f, 0f), vectors[0].values)
            assertEquals(listOf(0f, 1f), vectors[1].values)
        } finally {
            client.close()
        }
        Unit
    }

    @Test
    fun `rejects wrong count empty vectors and mixed dimensions`() = runBlocking {
        val bodies = ArrayDeque(listOf(
            """{"embeddings":[[1.0,0.0]]}""",
            """{"embeddings":[[],[]]}""",
            """{"embeddings":[[1.0],[0.0,1.0]]}""",
            """{"embeddings":[[NaN],[0.0]]}""",
        ))
        val client = HttpClient(MockEngine {
            respond(bodies.removeFirst(), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        try {
            assertFailsWith<IllegalArgumentException> { OllamaEmbeddingClient(client).embed(listOf("a", "b")) }
            assertFailsWith<IllegalArgumentException> { OllamaEmbeddingClient(client).embed(listOf("a", "b")) }
            assertFailsWith<IllegalArgumentException> { OllamaEmbeddingClient(client).embed(listOf("a", "b")) }
            assertFailsWith<IllegalArgumentException> { OllamaEmbeddingClient(client).embed(listOf("a", "b")) }
        } finally {
            client.close()
        }
        Unit
    }

    @Test
    fun `http errors are safe and identify a missing model without response body leakage`() = runBlocking {
        val secretBody = "provider-internal-secret"
        val client = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound, secretBody) })
        try {
            val error = assertFailsWith<EmbeddingApiException> {
                OllamaEmbeddingClient(client).embed(listOf("text"))
            }
            assertContains(error.message.orEmpty(), "ollama pull qwen3-embedding:0.6b")
            assertFalse(secretBody in error.message.orEmpty())
            assertEquals(404, error.statusCode)
        } finally {
            client.close()
        }
    }

    @Test
    fun `network failure becomes a safe Ollama availability error`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("internal socket detail") })
        try {
            val error = assertFailsWith<EmbeddingApiException> {
                OllamaEmbeddingClient(client).embed(listOf("text"))
            }
            assertContains(error.message.orEmpty(), "127.0.0.1:11434")
            assertFalse("internal socket detail" in error.message.orEmpty())
        } finally {
            client.close()
        }
        Unit
    }

    @Test
    fun `cancellation propagates from transport`() = runBlocking {
        val client = HttpClient(MockEngine { awaitCancellation() })
        try {
            val job = async { OllamaEmbeddingClient(client).embed(listOf("text")) }
            yield()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        } finally {
            client.close()
        }
        Unit
    }
}
