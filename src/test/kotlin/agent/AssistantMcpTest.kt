package org.example.agent

import kotlinx.coroutines.runBlocking
import org.example.llm.*
import org.example.mcp.LocalMcpGateway
import org.example.tokens.ContextOverflowPolicy
import kotlin.test.*

class AssistantMcpTest {
    @Test
    fun `MCP final answer still passes invariant structured postflight`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        val gateway = LocalMcpGateway()
        val invariant = AssistantInvariant("inv-stack", AssistantInvariantCategory.STACK, "Отвечай по-русски", 1)
        var step = 0
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                assertNotNull(options.structuredOutput)
                return if (step++ == 0) {
                    CompletionResult("", "tool_calls", TokenUsage(4, 1, 5), "test-model", listOf(
                        LlmToolCall("invariant-tool", "tracker_get_issue", "{\"issueId\":\"DEMO-101\"}"),
                    ))
                } else {
                    CompletionResult(
                        """{"stateVersion":1,"checkedInvariantIds":["inv-stack"],"conflictingInvariantIds":[],"decision":"COMPATIBLE","answer":"DEMO-101 находится в статусе In Progress.","audit":{"stateVersion":1,"checkedInvariantIds":["inv-stack"],"violatedInvariantIds":[],"answerCompliant":true}}""",
                        "stop", TokenUsage(12, 5, 17), "test-model",
                    )
                }
            }
        }
        val agent = AssistantAgent(
            memoryManager = memory,
            invariantStateProvider = { AssistantInvariantState(1, listOf(invariant)) },
            mcpGateway = gateway,
            clientProvider = { client },
        )
        try {
            val response = agent.respond(
                "Проверь DEMO-101 через трекер", "test-model", 200,
                ContextOverflowPolicy.REJECT, 10, mcpEnabled = true,
            )
            assertEquals("DEMO-101 находится в статусе In Progress.", response.completion.content)
            assertTrue(response.invariantDiagnostics.applied)
            assertFalse(response.invariantDiagnostics.responseBlocked)
            assertEquals(McpCallStatus.SUCCESS, response.mcpCalls.single().status)
            assertEquals(2, memory.state().shortTerm.size)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun `memory layers assistant uses real MCP and commits only the final pair`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        memory.add(MemoryLayer.LONG_TERM, "Отвечай по-русски")
        val gateway = LocalMcpGateway()
        val calls = mutableListOf<List<LlmMessage>>()
        var step = 0
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                calls += messages
                return if (step++ == 0) {
                    CompletionResult("", "tool_calls", TokenUsage(5, 2, 7), "test-model", listOf(
                        LlmToolCall("assistant-call", "tracker_get_issue", "{\"issueId\":\"DEMO-101\"}"),
                    ))
                } else {
                    assertEquals(LlmRole.TOOL, messages.last().role)
                    assertContains(messages.last().content, "In Progress")
                    CompletionResult("DEMO-101: In Progress. Далее — завершить сквозные тесты.",
                        "stop", TokenUsage(15, 6, 21), "test-model")
                }
            }
        }
        val agent = AssistantAgent(
            memoryManager = memory,
            mcpGateway = gateway,
            clientProvider = { client },
        )
        try {
            val response = agent.respond(
                prompt = "Получи через трекер данные DEMO-101",
                model = "test-model",
                maxTokens = 200,
                overflowPolicy = ContextOverflowPolicy.REJECT,
                shortTermMessageLimit = 10,
                mcpEnabled = true,
            )

            assertEquals(2, calls.size)
            assertEquals(McpCallStatus.SUCCESS, response.mcpCalls.single().status)
            assertEquals(20, response.completion.usage?.promptTokens)
            assertEquals(
                listOf("Получи через трекер данные DEMO-101", response.completion.content),
                memory.state().shortTerm.map(MemoryEntry::text),
            )
            assertTrue(memory.state().shortTerm.none { "nextAction" in it.text })
        } finally {
            gateway.close()
        }
    }
}
