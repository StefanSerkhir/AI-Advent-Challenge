package org.example.llm.openai

import kotlinx.serialization.encodeToString
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
}
