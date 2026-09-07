package org.example.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.example.llm.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmAgentTest {
    @Test
    fun `agent publishes accumulated streaming markdown and saves only the full answer`() = runBlocking {
        val client = StreamingClient(
            events = listOf(
                TextDelta("# Заг"),
                TextDelta("оловок\n\n**жир"),
                TextDelta("ный текст**"),
                CompletionFinished("stop", TokenUsage(4, 5, 9), "stream-model"),
            ),
        )
        val agent = LlmAgent { client }
        val updates = mutableListOf<String>()

        val response = agent.respond(AgentRequest("Покажи Markdown"), updates::add)

        assertEquals(
            listOf("# Заг", "# Заголовок\n\n**жир", "# Заголовок\n\n**жирный текст**"),
            updates,
        )
        assertEquals(updates.last(), response.content)
        assertEquals("stream-model", response.completion.model)
        assertEquals(
            listOf(
                LlmMessage(LlmRole.USER, "Покажи Markdown"),
                LlmMessage(LlmRole.ASSISTANT, updates.last()),
            ),
            agent.historySnapshot(),
        )
    }

    @Test
    fun `agent does not save a partial answer when the stream fails`() = runBlocking {
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
                error("complete must not be used")

            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("Незавершённый ответ"))
                throw LlmApiException("поток оборван")
            }
        }
        val agent = LlmAgent { client }
        val updates = mutableListOf<String>()

        assertFailsWith<LlmApiException> {
            agent.respond(AgentRequest("Вопрос"), updates::add)
        }

        assertEquals(listOf("Незавершённый ответ"), updates)
        assertTrue(agent.historySnapshot().isEmpty())
    }

    @Test
    fun `agent accepts a prompt calls the LLM and returns its answer`() = runBlocking {
        val client = RecordingClient()
        val agent: Agent = LlmAgent { client }
        val options = CompletionOptions(maxTokens = 120)

        val response = agent.respond(AgentRequest("  Привет!  ", options))

        assertEquals("Ответ от API", response.content)
        assertEquals("test-model", response.completion.model)
        assertEquals(listOf(LlmMessage(LlmRole.USER, "Привет!")), client.calls.single().messages)
        assertEquals(options, client.calls.single().options)
    }

    @Test
    fun `agent owns conversation history and can disable or clear it`() = runBlocking {
        val client = RecordingClient()
        val agent = LlmAgent { client }

        agent.respond(AgentRequest("Первый вопрос"))
        agent.respond(AgentRequest("Второй вопрос"))
        agent.respond(AgentRequest("Отдельный вопрос", historyEnabled = false))

        assertEquals(
            listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER),
            client.calls[1].messages.map { it.role },
        )
        assertEquals(1, client.calls[2].messages.size)
        assertEquals(2, agent.completedTurnCount())

        agent.clearHistory()

        assertTrue(agent.historySnapshot().isEmpty())
        assertEquals(0, agent.completedTurnCount())
    }

    @Test
    fun `agent validates the user request before calling the API`() = runBlocking {
        val client = RecordingClient()
        val agent = LlmAgent { client }

        assertFailsWith<IllegalArgumentException> {
            agent.respond(AgentRequest("   "))
        }
        assertTrue(client.calls.isEmpty())
    }

    private class RecordingClient : LlmClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(
            messages: List<LlmMessage>,
            options: CompletionOptions,
        ): CompletionResult {
            calls += Call(messages, options)
            return CompletionResult(
                content = "Ответ от API",
                finishReason = "stop",
                usage = TokenUsage(3, 4, 7),
                model = "test-model",
            )
        }
    }

    private data class Call(val messages: List<LlmMessage>, val options: CompletionOptions)

    private class StreamingClient(
        private val events: List<CompletionEvent>,
    ) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
            error("complete must not be used")

        override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
            events.forEach { emit(it) }
        }
    }
}
