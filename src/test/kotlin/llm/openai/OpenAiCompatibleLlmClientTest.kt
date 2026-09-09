package org.example.llm.openai

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.example.llm.*
import org.example.llm.deepseek.DeepSeekLlmClient
import kotlin.test.*

class OpenAiCompatibleLlmClientTest {
    @Test
    fun `stream sends streaming options and emits text deltas before final metadata`() = runBlocking {
        lateinit var request: JsonObject
        val http = HttpClient(MockEngine { captured ->
            request = Json.parseToJsonElement((captured.body as TextContent).text).jsonObject
            respond(
                content = """
                    data: {"id":"chatcmpl-test","object":"chat.completion.chunk","model":"gpt-test","choices":[{"index":0,"delta":{"role":"assistant","content":"# Заг"},"finish_reason":null}]}

                    data: {"id":"chatcmpl-test","object":"chat.completion.chunk","model":"gpt-test","choices":[{"index":0,"delta":{"content":"оловок"},"finish_reason":null}]}

                    data: {"id":"chatcmpl-test","object":"chat.completion.chunk","model":"gpt-test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: {"id":"chatcmpl-test","object":"chat.completion.chunk","model":"gpt-test","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10,"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":1}}}

                    data: [DONE]

                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }) {
            install(ContentNegotiation) {
                // Keep this aligned with createHttpClient(): default-valued fields are not encoded.
                json(Json { ignoreUnknownKeys = true })
            }
        }
        try {
            val events = OpenAiLlmClient("fake-key", http, "gpt-test")
                .stream("Привет")
                .toList()

            assertEquals(true, request["stream"]?.jsonPrimitive?.boolean)
            assertEquals(true, request["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.boolean)
            assertEquals(listOf("# Заг", "оловок"), events.filterIsInstance<TextDelta>().map { it.text })
            val finished = assertIs<CompletionFinished>(events.last())
            assertEquals("stop", finished.finishReason)
            assertEquals("gpt-test", finished.model)
            assertEquals(TokenUsage(7, 3, 10, cachedPromptTokens = 2, reasoningTokens = 1), finished.usage)
        } finally {
            http.close()
        }
    }

    @Test
    fun `stream rejects an SSE response that ends without done`() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(
                content = "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\"часть\"}}]}\n\n",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }) { install(ContentNegotiation) { json() } }
        try {
            val error = assertFailsWith<LlmApiException> {
                OpenAiLlmClient("fake-key", http).stream("Привет").toList()
            }
            assertContains(error.message.orEmpty(), "оборвался")
        } finally {
            http.close()
        }
    }

    @Test
    fun `provider context overflow is a structured error instead of generic 400`() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(
                content = """{"error":{"message":"This model's maximum context length was exceeded","type":"invalid_request_error","code":"context_length_exceeded"}}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) { install(ContentNegotiation) { json() } }
        try {
            val error = assertFailsWith<LlmContextApiException> { OpenAiLlmClient("fake-key", http).complete("too long") }
            assertEquals(400, error.providerStatus)
            assertContains(error.message.orEmpty(), "контекстного окна")
            assertFalse(error.message.orEmpty().contains("LLM API вернул ошибку 400"))
        } finally { http.close() }
    }

    @Test
    fun `real provider adapters preserve fallback stop and reasoning request parameters`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine { request ->
            requests += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("""{"choices":[{"message":{"role":"assistant","content":"Ответ"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6},"model":"actual-model"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        try {
            val openai = OpenAiLlmClient("fake-key", http, "gpt-5.6-luna")
            val response = openai.complete("temperature", CompletionOptions(temperature = 1.2))
            assertEquals("gpt-4.1-mini", requests[0]["model"]?.jsonPrimitive?.content)
            assertEquals(1.2, requests[0]["temperature"]?.jsonPrimitive?.double)
            assertEquals("actual-model", response.model)
            assertEquals(2, response.usage?.completionTokens)
            openai.complete("controlled", CompletionOptions(maxTokens = 500, stopSequences = listOf("END"), reasoningEffort = ReasoningEffort.MEDIUM))
            assertEquals("gpt-5.6-luna", requests[1]["model"]?.jsonPrimitive?.content)
            assertEquals(500, requests[1]["max_completion_tokens"]?.jsonPrimitive?.int)
            assertEquals("medium", requests[1]["reasoning_effort"]?.jsonPrimitive?.content)
            assertTrue(requests[1]["stop"] == null || requests[1]["stop"] == JsonNull)
            DeepSeekLlmClient("fake-key", http).complete("controlled", CompletionOptions(maxTokens = 200, stopSequences = listOf("END")))
            assertEquals("deepseek-v4-flash", requests[2]["model"]?.jsonPrimitive?.content)
            assertEquals(200, requests[2]["max_tokens"]?.jsonPrimitive?.int)
            assertEquals("END", requests[2]["stop"]?.jsonArray?.single()?.jsonPrimitive?.content)
        } finally { http.close() }
    }
}
