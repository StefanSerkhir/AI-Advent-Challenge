package org.example.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.example.llm.*
import kotlin.test.*

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
        var persistedHistory = emptyList<LlmMessage>()
        val agent = LlmAgent(
            persistHistory = { persistedHistory = it },
            clientProvider = { client },
        )
        val updates = mutableListOf<String>()
        val metricUpdates = mutableListOf<org.example.tokens.TurnTokenMetrics>()

        val response = agent.respond(AgentRequest("Покажи Markdown", model = "gpt-5.6-sol"), updates::add, metricUpdates::add)

        assertEquals(
            listOf("# Заг", "# Заголовок\n\n**жир", "# Заголовок\n\n**жирный текст**"),
            updates,
        )
        assertEquals(updates.last(), response.content)
        assertEquals("stream-model", response.completion.model)
        val expectedHistory = listOf(
            LlmMessage(LlmRole.USER, "Покажи Markdown"),
            LlmMessage(LlmRole.ASSISTANT, updates.last()),
        )
        assertEquals(expectedHistory, agent.historySnapshot())
        assertEquals(expectedHistory, persistedHistory)
        assertNull(metricUpdates.first().actualUsage)
        assertEquals(4, metricUpdates.last().actualUsage?.promptTokens)
        assertEquals(response.tokenMetrics, metricUpdates.last())
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
        var persistenceCalls = 0
        val agent = LlmAgent(persistHistory = { persistenceCalls++ }, clientProvider = { client })
        val updates = mutableListOf<String>()

        assertFailsWith<LlmApiException> {
            agent.respond(AgentRequest("Вопрос"), updates::add)
        }

        assertEquals(listOf("Незавершённый ответ"), updates)
        assertTrue(agent.historySnapshot().isEmpty())
        assertEquals(0, persistenceCalls)
    }

    @Test
    fun `agent does not persist or remember a cancelled stream`() = runBlocking {
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
                error("complete must not be used")

            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("часть ответа"))
                awaitCancellation()
            }
        }
        var persistenceCalls = 0
        val agent = LlmAgent(persistHistory = { persistenceCalls++ }, clientProvider = { client })

        assertFailsWith<CancellationException> {
            kotlinx.coroutines.withTimeout(250) { agent.respond(AgentRequest("Вопрос")) }
        }

        assertEquals(0, persistenceCalls)
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
