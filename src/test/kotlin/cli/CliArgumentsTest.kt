package org.example.cli

import org.example.llm.LlmKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CliArgumentsTest {

    @Test
    fun `uses DeepSeek by default`() {
        val arguments = parseCliArguments(
            args = arrayOf("--llm_api_key=test-key"),
        )

        assertEquals(LlmKind.DEEPSEEK, arguments.llmKind)
    }

    @Test
    fun `uses kind from local config`() {
        val arguments = parseCliArguments(
            args = arrayOf("--llm_api_key=test-key"),
            defaultLlmKind = "OpenAI",
        )

        assertEquals(LlmKind.OPENAI, arguments.llmKind)
    }

    @Test
    fun `CLI kind overrides local config`() {
        val arguments = parseCliArguments(
            args = arrayOf("--llm_api_key=test-key", "--llm_kind=Deepseek"),
            defaultLlmKind = "OpenAI",
        )

        assertEquals(LlmKind.DEEPSEEK, arguments.llmKind)
    }

    @Test
    fun `allows starting interactive mode without key or prompt`() {
        val arguments = parseCliArguments(emptyArray())

        assertNull(arguments.apiKey)
        assertNull(arguments.prompt)
    }

    @Test
    fun `uses positional arguments as initial prompt`() {
        val arguments = parseCliArguments(
            arrayOf("Объясни", "корутины"),
        )

        assertEquals("Объясни корутины", arguments.prompt)
    }
}
