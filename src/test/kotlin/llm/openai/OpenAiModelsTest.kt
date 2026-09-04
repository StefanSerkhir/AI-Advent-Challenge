package org.example.llm.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenAiModelsTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `unrestricted request omits control parameters`() {
        val request = ChatCompletionRequest(
            model = "test-model",
            messages = listOf(ChatMessage("user", "test prompt")),
        )

        val body = json.encodeToString(request)

        assertFalse("max_tokens" in body)
        assertFalse("max_completion_tokens" in body)
        assertFalse("stop" in body)
    }

    @Test
    fun `controlled request serializes token limit and stop sequence`() {
        val request = ChatCompletionRequest(
            model = "test-model",
            messages = listOf(ChatMessage("user", "test prompt")),
            maxTokens = 120,
            stop = listOf("<END_OF_RESPONSE>"),
        )

        val body = Json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals(120, body.getValue("max_tokens").toString().toInt())
        assertEquals("[\"<END_OF_RESPONSE>\"]", body.getValue("stop").toString())
    }

    @Test
    fun `temperature request serializes sampling value`() {
        val request = ChatCompletionRequest(
            model = "test-model",
            messages = listOf(ChatMessage("user", "test prompt")),
            temperature = 0.7,
        )

        val body = Json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("0.7", body.getValue("temperature").toString())
    }

    @Test
    fun `OpenAI request can serialize completion token limit`() {
        val request = ChatCompletionRequest(
            model = "test-model",
            messages = listOf(ChatMessage("user", "test prompt")),
            maxCompletionTokens = 120,
        )

        val body = json.encodeToString(request)

        assertFalse("\"max_tokens\"" in body)
        assertEquals(true, "\"max_completion_tokens\":120" in body)
    }

    @Test
    fun `reasoning request serializes effort`() {
        val request = ChatCompletionRequest(
            model = "test-model",
            messages = listOf(ChatMessage("user", "test prompt")),
            reasoningEffort = "medium",
        )

        val body = json.encodeToString(request)

        assertEquals(true, "\"reasoning_effort\":\"medium\"" in body)
    }

    @Test
    fun `response deserializes usage and finish reason`() {
        val response = json.decodeFromString<ChatCompletionResponse>(
            """
            {
              "choices": [{
                "message": {"role": "assistant", "content": "Готово"},
                "finish_reason": "stop"
              }],
              "usage": {
                "prompt_tokens": 11,
                "completion_tokens": 3,
                "total_tokens": 14,
                "prompt_tokens_details": {
                  "cached_tokens": 4,
                  "cache_write_tokens": 2
                },
                "completion_tokens_details": {
                  "reasoning_tokens": 1
                }
              },
              "model": "gpt-4.1-mini-2025-04-14"
            }
            """.trimIndent(),
        )

        assertEquals("stop", response.choices.single().finishReason)
        assertEquals(3, response.usage?.completionTokens)
        assertEquals(4, response.usage?.promptTokensDetails?.cachedTokens)
        assertEquals(2, response.usage?.promptTokensDetails?.cacheWriteTokens)
        assertEquals(1, response.usage?.completionTokensDetails?.reasoningTokens)
        assertEquals("gpt-4.1-mini-2025-04-14", response.model)
    }
}
