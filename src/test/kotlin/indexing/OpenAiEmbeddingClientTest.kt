package org.example.indexing

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class OpenAiEmbeddingClientTest {
    @Test
    fun `response is restored by index and transient rate limit is retried`() = runBlocking {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respondError(HttpStatusCode.TooManyRequests)
            else respond(
                content = """{"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine)
        try {
            val vectors = OpenAiEmbeddingClient(
                apiKey = "test-secret-key",
                httpClient = client,
                endpoint = "https://example.test/v1/embeddings",
                retryDelayMillis = {},
            ).embed(listOf("first", "second"))

            assertEquals(2, calls)
            assertEquals(listOf(1f, 0f), vectors[0].values)
            assertEquals(listOf(0f, 1f), vectors[1].values)
        } finally {
            client.close()
        }
    }

    @Test
    fun `provider error redacts configured key bearer tokens and sk values`() = runBlocking {
        val secret = "configured-super-secret"
        val engine = MockEngine {
            respond(
                """{"error":{"message":"bad $secret Bearer abc.def sk-12345678901234567890"}}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine)
        try {
            val error = assertFails {
                OpenAiEmbeddingClient(secret, client, endpoint = "https://example.test").embed(listOf("text"))
            }
            val message = error.message.orEmpty()
            assertFalse(secret in message)
            assertFalse("abc.def" in message)
            assertFalse("sk-12345678901234567890" in message)
        } finally {
            client.close()
        }
    }

    @Test
    fun `mismatched dimensions and missing response indices are rejected`() = runBlocking {
        val bodies = ArrayDeque(listOf(
            """{"data":[{"index":0,"embedding":[1.0]},{"index":1,"embedding":[0.0,1.0]}]}""",
            """{"data":[{"index":0,"embedding":[1.0]},{"index":0,"embedding":[1.0]}]}""",
        ))
        val client = HttpClient(MockEngine {
            respond(bodies.removeFirst(), headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        try {
            assertFails { OpenAiEmbeddingClient("key", client, endpoint = "https://example.test").embed(listOf("a", "b")) }
            assertFails { OpenAiEmbeddingClient("key", client, endpoint = "https://example.test").embed(listOf("a", "b")) }
        } finally {
            client.close()
        }
    }
}
