package org.example.app

import kotlinx.coroutines.runBlocking
import org.example.llm.CompletionOptions
import org.example.llm.CompletionResult
import org.example.llm.LlmClient
import org.example.llm.LlmKind
import org.example.llm.LlmMessage
import org.example.llm.LlmRole
import org.example.llm.TokenUsage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptRunnerTest {

    @Test
    fun `compare mode keeps independent history for both variants`() = runBlocking {
        val client = RecordingLlmClient()
        val runner = PromptRunner { client }
        val settings = AppSettings(llmKind = LlmKind.OPENAI)

        runner.complete("Первый вопрос", settings)
        runner.complete("Второй вопрос", settings)

        assertEquals(4, client.calls.size)
        assertEquals("Первый вопрос", client.calls[0].messages.single().content)
        assertContains(client.calls[1].messages.single().content, "Требования к ответу")
        assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER), client.calls[2].messages.map { it.role })
        assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER), client.calls[3].messages.map { it.role })
        assertEquals("answer-1", client.calls[2].messages[1].content)
        assertEquals("answer-2", client.calls[3].messages[1].content)
        assertNull(client.calls[0].options.maxTokens)
        assertEquals(300, client.calls[1].options.maxTokens)
    }

    @Test
    fun `history can be disabled and cleared`() = runBlocking {
        val client = RecordingLlmClient()
        val runner = PromptRunner { client }
        val settings = AppSettings(llmKind = LlmKind.OPENAI)

        runner.complete("Сохрани", settings)
        settings.historyEnabled = false
        runner.complete("Без истории", settings)

        assertEquals(1, client.calls[2].messages.size)
        assertEquals(1, client.calls[3].messages.size)

        runner.clearHistory()
        settings.historyEnabled = true
        runner.complete("После очистки", settings)

        assertEquals(1, client.calls[4].messages.size)
        assertEquals(1, client.calls[5].messages.size)
    }

    private class RecordingLlmClient : LlmClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(
            messages: List<LlmMessage>,
            options: CompletionOptions,
        ): CompletionResult {
            calls += Call(messages, options)
            return CompletionResult(
                content = "answer-${calls.size}",
                finishReason = "stop",
                usage = TokenUsage(10, calls.size, 10 + calls.size),
            )
        }
    }

    private data class Call(
        val messages: List<LlmMessage>,
        val options: CompletionOptions,
    )
}
