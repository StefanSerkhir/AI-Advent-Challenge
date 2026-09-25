package org.example.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.example.llm.*
import org.example.mcp.LocalMcpGateway
import org.example.mcp.SAVE_TO_FILE_TOOL
import org.example.mcp.SEARCH_TOOL
import org.example.mcp.SUMMARIZE_TOOL
import org.example.tokens.ModelContextProfiles
import org.example.tokens.TokenCostCalculator
import java.nio.file.Files
import kotlin.test.*

class LlmAgentTest {
    @Test
    fun `multi-step MCP cost is summed per request before high-context pricing`() {
        val calculator = TokenCostCalculator()
        val profile = requireNotNull(ModelContextProfiles.find("gpt-5.6-sol"))
        val stepUsage = TokenUsage(200_000, 100, 200_100)
        val expected = requireNotNull(calculator.calculate(stepUsage, profile)).multiply(java.math.BigDecimal(2))

        val actual = aggregateStepCost(
            listOf(LlmCallStep(stepUsage, profile.modelId), LlmCallStep(stepUsage, profile.modelId)),
            ModelContextProfiles::find,
            calculator,
            profile.modelId,
        )
        val incorrectlyTieredAggregate = calculator.calculate(TokenUsage(400_000, 200, 400_200), profile)

        assertEquals(expected, actual)
        assertNotEquals(incorrectlyTieredAggregate, actual)
    }

    @Test
    fun `agent performs a real MCP call and persists only user plus final assistant`() = runBlocking {
        val directory = Files.createTempDirectory("llm-agent-mcp-test")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"), directory.resolve("output"))
        val calls = mutableListOf<List<LlmMessage>>()
        var step = 0
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                calls += messages
                return if (step++ == 0) {
                    assertTrue(options.tools.any { it.name == "tracker_get_issue" })
                    CompletionResult("", "tool_calls", TokenUsage(10, 3, 13), "test-model", listOf(
                        LlmToolCall("call-1", "tracker_get_issue", "{\"issueId\":\"DEMO-101\"}"),
                    ))
                } else {
                    val tool = messages.last()
                    assertEquals(LlmRole.TOOL, tool.role)
                    assertContains(tool.content, "UNTRUSTED MCP TOOL DATA")
                    assertContains(tool.content, "\"status\":\"In Progress\"")
                    assertContains(tool.content, "\"nextAction\"")
                    CompletionResult("Статус: In Progress. Следующее действие: завершить сквозные тесты.",
                        "stop", TokenUsage(30, 8, 38), "test-model")
                }
            }
        }
        var persisted = emptyList<LlmMessage>()
        val agent = LlmAgent(
            persistHistory = { persisted = it },
            mcpGateway = gateway,
            clientProvider = { client },
        )
        try {
            val response = agent.respond(AgentRequest(
                "Получи через трекер DEMO-101", model = "test-model", mcpEnabled = true,
            ))

            assertEquals(2, calls.size)
            assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.TOOL), calls[1].map(LlmMessage::role))
            assertEquals(40, response.completion.usage?.promptTokens)
            assertEquals(11, response.completion.usage?.completionTokens)
            assertEquals(51, response.completion.usage?.totalTokens)
            assertEquals(McpCallStatus.SUCCESS, response.mcpCalls.single().status)
            assertEquals("tracker_get_issue", response.mcpCalls.single().toolName)
            assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT), persisted.map(LlmMessage::role))
            assertEquals(listOf("Получи через трекер DEMO-101", response.content), persisted.map(LlmMessage::content))
            assertTrue(persisted.none { it.role == LlmRole.TOOL || it.toolCalls.isNotEmpty() })
        } finally {
            gateway.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `agent composes search summarize and save results through transient tool messages`() = runBlocking {
        val directory = Files.createTempDirectory("llm-agent-pipeline-test")
        val outputDirectory = directory.resolve("output")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"), outputDirectory)
        val calls = mutableListOf<List<LlmMessage>>()
        var expectedSourceIds = emptyList<JsonElement>()
        var expectedSummary = ""
        var step = 0
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                calls += messages
                return when (step++) {
                    0 -> {
                        assertTrue(options.tools.map { it.name }.containsAll(listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TO_FILE_TOOL)))
                        assertTrue(messages.none { it.role == LlmRole.TOOL })
                        CompletionResult("", "tool_calls", TokenUsage(10, 2, 12), "test-model", listOf(
                            LlmToolCall("pipeline-search", SEARCH_TOOL, "{\"query\":\"композиция MCP-инструментов\"}"),
                        ))
                    }
                    1 -> {
                        val searchMessage = messages.last()
                        assertEquals(LlmRole.TOOL, searchMessage.role)
                        assertEquals(SEARCH_TOOL, searchMessage.name)
                        assertContains(searchMessage.content, "UNTRUSTED MCP TOOL DATA")
                        val searchResult = Json.parseToJsonElement(searchMessage.content.substringAfter('\n')).jsonObject
                        val matches = searchResult["matches"]!!.jsonArray
                        expectedSourceIds = matches.map { it.jsonObject["id"]!! }
                        assertTrue(expectedSourceIds.isNotEmpty())
                        CompletionResult("", "tool_calls", TokenUsage(12, 2, 14), "test-model", listOf(
                            LlmToolCall("pipeline-summarize", SUMMARIZE_TOOL, buildJsonObject {
                                put("matches", matches)
                                put("maxSentences", 3)
                            }.toString()),
                        ))
                    }
                    2 -> {
                        val summaryMessage = messages.last()
                        assertEquals(LlmRole.TOOL, summaryMessage.role)
                        assertEquals(SUMMARIZE_TOOL, summaryMessage.name)
                        val summaryResult = Json.parseToJsonElement(summaryMessage.content.substringAfter('\n')).jsonObject
                        expectedSummary = summaryResult["summary"]!!.jsonPrimitive.content
                        assertEquals(expectedSourceIds, summaryResult["sourceIds"]!!.jsonArray)
                        assertContains(expectedSummary, "Композиция MCP-инструментов")
                        CompletionResult("", "tool_calls", TokenUsage(14, 2, 16), "test-model", listOf(
                            LlmToolCall("pipeline-save", SAVE_TO_FILE_TOOL, buildJsonObject {
                                put("fileName", "pipeline-summary.md")
                                put("content", expectedSummary)
                                put("sourceIds", summaryResult["sourceIds"]!!)
                            }.toString()),
                        ))
                    }
                    3 -> {
                        val saveMessage = messages.last()
                        assertEquals(LlmRole.TOOL, saveMessage.role)
                        assertEquals(SAVE_TO_FILE_TOOL, saveMessage.name)
                        val saveResult = Json.parseToJsonElement(saveMessage.content.substringAfter('\n')).jsonObject
                        assertEquals("pipeline-summary.md", saveResult["fileName"]?.jsonPrimitive?.content)
                        assertEquals(expectedSourceIds, saveResult["sourceIds"]!!.jsonArray)
                        assertEquals(3, messages.count { it.role == LlmRole.TOOL })
                        CompletionResult(
                            "Локальные сведения найдены, суммированы и сохранены в pipeline-summary.md.",
                            "stop",
                            TokenUsage(16, 8, 24),
                            "test-model",
                        )
                    }
                    else -> error("Unexpected LLM step")
                }
            }
        }
        var persisted = emptyList<LlmMessage>()
        val agent = LlmAgent(
            persistHistory = { persisted = it },
            mcpGateway = gateway,
            clientProvider = { client },
        )
        try {
            val prompt = "Найди локальные сведения о композиции MCP-инструментов, кратко суммируй их и сохрани в pipeline-summary.md"
            val response = agent.respond(AgentRequest(prompt, model = "test-model", mcpEnabled = true))

            assertEquals(4, calls.size)
            assertEquals(listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TO_FILE_TOOL), response.mcpCalls.map { it.toolName })
            assertTrue(response.mcpCalls.all { it.status == McpCallStatus.SUCCESS })
            assertContains(response.mcpCalls[0].result, "mcp-composition-001")
            assertContains(response.mcpCalls[1].result, "sourceIds")
            assertContains(response.mcpCalls[2].result, "pipeline-summary.md")
            assertEquals(expectedSummary, Files.readString(outputDirectory.resolve("pipeline-summary.md")))
            assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT), persisted.map { it.role })
            assertEquals(listOf(prompt, response.content), persisted.map { it.content })
            assertTrue(persisted.none { it.role == LlmRole.TOOL || it.toolCalls.isNotEmpty() })
        } finally {
            gateway.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `MCP tool error is diagnosed and final response is not persisted`() = runBlocking {
        val directory = Files.createTempDirectory("llm-agent-mcp-test")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"), directory.resolve("output"))
        var step = 0
        var persistenceCalls = 0
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = if (step++ == 0) {
                CompletionResult("", "tool_calls", TokenUsage(2, 1, 3), "test-model", listOf(
                    LlmToolCall("missing", "tracker_get_issue", "{\"issueId\":\"DEMO-404\"}"),
                ))
            } else {
                assertContains(messages.last().content, "was not found")
                CompletionResult("Задача не найдена.", "stop", TokenUsage(3, 2, 5), "test-model")
            }
        }
        val agent = LlmAgent(
            persistHistory = { persistenceCalls++ },
            mcpGateway = gateway,
            clientProvider = { client },
        )
        try {
            val response = agent.respond(AgentRequest("Найди DEMO-404", mcpEnabled = true))
            assertEquals(McpCallStatus.ERROR, response.mcpCalls.single().status)
            assertEquals("Задача не найдена.", response.content)
            assertTrue(agent.historySnapshot().isEmpty())
            assertEquals(0, persistenceCalls)
        } finally {
            gateway.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `agent rejects a fourth MCP call without committing history`() = runBlocking {
        val directory = Files.createTempDirectory("llm-agent-mcp-test")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"), directory.resolve("output"))
        var step = 0
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = CompletionResult(
                "", "tool_calls", TokenUsage(1, 1, 2), "test-model",
                listOf(LlmToolCall("ping-${step++}", "ping", "{}")),
            )
        }
        val agent = LlmAgent(mcpGateway = gateway, clientProvider = { client })
        try {
            val error = assertFailsWith<LlmApiException> {
                agent.respond(AgentRequest("Проверь MCP", mcpEnabled = true))
            }
            assertContains(error.message.orEmpty(), "не более 3")
            assertEquals(4, step)
            assertTrue(agent.historySnapshot().isEmpty())
        } finally {
            gateway.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cancellation during MCP loop does not commit history`() = runBlocking {
        val directory = Files.createTempDirectory("llm-agent-mcp-test")
        val gateway = LocalMcpGateway(directory.resolve("scheduler.json"), directory.resolve("output"))
        var step = 0
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = error("unused")
            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                if (step++ == 0) {
                    emit(CompletionFinished("tool_calls", TokenUsage(1, 1, 2), "test-model", listOf(
                        LlmToolCall("ping-cancel", "ping", "{}"),
                    )))
                } else {
                    awaitCancellation()
                }
            }
        }
        val agent = LlmAgent(mcpGateway = gateway, clientProvider = { client })
        try {
            assertFailsWith<CancellationException> {
                kotlinx.coroutines.withTimeout(500) {
                    agent.respond(AgentRequest("Проверь отмену MCP", mcpEnabled = true))
                }
            }
            assertTrue(agent.historySnapshot().isEmpty())
        } finally {
            gateway.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sliding strategy controls request and persistent agent memory`() = runBlocking {
        val client = RecordingClient()
        val manager = ContextManager(InMemoryContextStateStore(), FactExtractor { _, _ -> FactPatch() })
        val agent = LlmAgent(contextManager = manager, contextSessionId = "ui-agent", clientProvider = { client })
        val request: (String) -> AgentRequest = { AgentRequest(it, contextStrategy = ContextStrategy.SLIDING_WINDOW, recentMessagesLimit = 2) }

        agent.respond(request("first"))
        agent.respond(request("second"))

        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.ASSISTANT, LlmRole.USER), client.calls.last().messages.map { it.role })
        assertEquals(listOf("Ответ от API", "second"), client.calls.last().messages.takeLast(2).map { it.content })
        assertEquals(listOf("second", "Ответ от API"), agent.historySnapshot().map { it.content })
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
    fun `sticky facts and raw history remain unchanged when main stream fails`() = runBlocking {
        val manager = ContextManager(InMemoryContextStateStore(), FactExtractor { _, _ ->
            FactPatch(mapOf("goal" to "release"), usage = TokenUsage(3, 2, 5))
        })
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = error("unused")
            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("partial")); throw LlmApiException("failed")
            }
        }
        val agent = LlmAgent(contextManager = manager, contextSessionId = "s", clientProvider = { client })

        assertFailsWith<LlmApiException> {
            agent.respond(AgentRequest("remember goal", contextStrategy = ContextStrategy.STICKY_FACTS, recentMessagesLimit = 2))
        }

        assertTrue(manager.state("s").stickyFacts.isEmpty())
        assertTrue(manager.state("s").stickyMessages.isEmpty())
        assertEquals(1, manager.state("s").factUsage.requests)
        assertTrue(agent.historySnapshot().isEmpty())
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
    fun `cancelled sticky request does not commit candidate facts`() = runBlocking {
        val manager = ContextManager(InMemoryContextStateStore(), FactExtractor { _, _ -> FactPatch(mapOf("name" to "Анна")) })
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = error("unused")
            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("часть")); awaitCancellation()
            }
        }
        val agent = LlmAgent(contextManager = manager, contextSessionId = "s", clientProvider = { client })

        assertFailsWith<CancellationException> {
            kotlinx.coroutines.withTimeout(100) {
                agent.respond(AgentRequest("Меня зовут Анна", contextStrategy = ContextStrategy.STICKY_FACTS))
            }
        }

        assertTrue(manager.state("s").stickyFacts.isEmpty())
        assertTrue(manager.state("s").stickyMessages.isEmpty())
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
