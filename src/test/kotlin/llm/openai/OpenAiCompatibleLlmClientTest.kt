package org.example.llm.openai

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.example.llm.*
import org.example.llm.deepseek.DeepSeekLlmClient
import kotlin.test.*

class OpenAiCompatibleLlmClientTest {
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
