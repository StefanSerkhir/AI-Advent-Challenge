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
    fun `managed context replaces old raw messages with summary in the next agent request`() = runBlocking {
        val mainCalls = mutableListOf<List<LlmMessage>>()
        var summaryCalls = 0
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                return if (messages.firstOrNull()?.content == SUMMARY_SYSTEM_PROMPT) {
                    summaryCalls++
                    CompletionResult("summary: first fact", "stop", TokenUsage(12, 3, 15), "test-model")
                } else {
                    mainCalls += messages
                    CompletionResult("answer-${mainCalls.size}", "stop", TokenUsage(10, 2, 12), "test-model")
                }
            }
        }
        val manager = ContextManager(
            ContextCompressionConfig(enabled = false),
            InMemoryContextStateStore(),
            LlmHistorySummarizer(clientProvider = { client }),
        )
        val agent = LlmAgent(contextManager = manager, contextSessionId = "ui-agent", clientProvider = { client })
        val request: (String) -> AgentRequest = { prompt ->
            AgentRequest(prompt, contextManagementEnabled = true, recentMessagesLimit = 2, summarizationBatchSize = 2)
        }

        agent.respond(request("first fact"))
        agent.respond(request("second fact"))
        assertEquals(1, summaryCalls)
        agent.respond(request("current question"))

        assertEquals(2, summaryCalls)
        val sent = mainCalls.last()
        assertEquals(
            listOf(LlmRole.SYSTEM, LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER),
            sent.map(LlmMessage::role),
        )
        assertContains(sent[1].content, "summary: first fact")
        assertFalse(sent.any { it.content == "first fact" })
        assertEquals(listOf("second fact", "answer-2", "current question"), sent.takeLast(3).map(LlmMessage::content))
        assertEquals(6, agent.historySnapshot().size)
        val savings = agent.contextSavingsSnapshot()
        assertEquals(3, savings.mainRequests)
        assertEquals(2, savings.summarizationRequests)
        assertEquals(30, savings.compressedMainInputTokens)
        assertEquals(6, savings.compressedMainOutputTokens)
        assertEquals(30, savings.summaryTotalTokens)
        assertEquals(66, savings.compressedTotalTokens)
        assertTrue(savings.baselineEstimatedInputTokens > 0)
        assertNotNull(savings.savingPercent)
    }

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
