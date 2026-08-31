package org.example.cli

import org.example.llm.LlmKind
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
