package org.example.web

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.example.agent.InMemoryAssistantMemoryStore
import org.example.app.AppSettings
import org.example.app.ResponseMode
import org.example.app.WorkbenchController
import org.example.llm.*
import org.example.mcp.GIT_ACTIVITY_REPORT_TOOL
import org.example.mcp.McpGateway
import org.example.mcp.McpTool
import org.example.mcp.McpToolResult
import java.util.*
import kotlin.test.*

class ProjectDigestApiTest {
    @Test
    fun `digest maps through state and sse dto without committing history or memory`() = runBlocking {
        var mcpCalls = 0
        var llmCalls = 0
        val controller = controller(
            gateway = object : McpGateway {
                override suspend fun listTools() = listOf(McpTool(
                    GIT_ACTIVITY_REPORT_TOOL, "digest", buildJsonObject {}, "workspace",
                ))
                override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                    mcpCalls++
                    return McpToolResult(false, API_DIGEST_REPORT)
                }
                override suspend fun close() = Unit
            },
            answer = { _, _, options ->
                llmCalls++
                assertTrue(options.tools.isEmpty())
                CompletionResult(API_DIGEST_ANSWER, "stop", TokenUsage(20, 10, 30), "fixture-model")
            },
        )
        try {
            val api = WorkbenchApi(controller)
            api.start(StartCommand(
                requestId = UUID.randomUUID().toString(),
                expectedSettingsVersion = controller.state.value.settingsVersion,
                prompt = "/digest 24",
            ))
            controller.awaitCurrentRequest()

            val state = controller.state.value.toDto()
            val exchange = state.exchanges.single()
            val output = exchange.outputs.single()
            assertEquals("completed", exchange.status)
            assertEquals("AI-СВОДКА РАЗРАБОТКИ", output.title)
            val diagnostics = assertNotNull(output.projectDigestDiagnostics)
            assertEquals(24, diagnostics.sinceHours)
            assertEquals("feature/digest", diagnostics.branch)
            assertEquals(1, diagnostics.commitCount)
            assertEquals(2, diagnostics.fileCount)
            assertEquals(listOf("C1", "F1", "F2"), diagnostics.sourceIds)
            assertEquals(GIT_ACTIVITY_REPORT_TOOL, output.mcpCalls.single().toolName)
            assertFalse(output.mcpCalls.single().result.contains("Digest.kt"))
            assertFalse(output.mcpCalls.single().result.contains("Добавлена сводка"))
            assertEquals(0, state.history.unrestricted)
            assertEquals(0, state.assistantMemory.layers.single { it.layer == "SHORT_TERM" }.count)
            assertContains(api.snapshot(state), "\"projectDigestDiagnostics\"")
            assertContains(api.snapshot(state), "\"sinceHours\":24")
            assertEquals(1, mcpCalls)
            assertEquals(1, llmCalls)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `help and invalid digest run without key while real digest is blocked`() = runBlocking {
        var touched = false
        val controller = WorkbenchController(
            initialSettings = AppSettings(LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            initialApiKeys = emptyMap(),
            mcpGateway = object : McpGateway {
                override suspend fun listTools(): List<McpTool> { touched = true; return emptyList() }
                override suspend fun callTool(name: String, arguments: JsonObject) = error("not reached")
                override suspend fun close() = Unit
            },
            clientFactory = { _, _, _ -> touched = true; error("not reached") },
        )
        try {
            assertTrue(controller.submit("/digest help"))
            controller.awaitCurrentRequest()
            assertContains(controller.state.value.toDto().exchanges.last().outputs.single().content.orEmpty(), "/digest <hours>")
            assertTrue(controller.submit("/digest 0"))
            controller.awaitCurrentRequest()
            assertContains(controller.state.value.toDto().exchanges.last().outputs.single().content.orEmpty(), "1 до 168")
            assertFalse(controller.submit("/digest 24"))
            assertContains(controller.state.value.toDto().notice?.message.orEmpty(), "API-ключ")
            assertFalse(touched)
            assertEquals(0, controller.state.value.toDto().history.unrestricted)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `digest error and cancellation do not commit history or short term memory`() = runBlocking {
        suspend fun assertIsolated(answer: suspend () -> CompletionResult, cancel: Boolean) {
            val controller = controller(
                gateway = StaticDigestGateway,
                answer = { _, _, _ -> answer() },
            )
            try {
                assertTrue(controller.submit("/digest 24"))
                if (cancel) {
                    yield()
                    controller.cancelCurrent()
                }
                controller.awaitCurrentRequest()
                val state = controller.state.value.toDto()
                assertEquals(0, state.history.unrestricted)
                assertEquals(0, state.assistantMemory.layers.single { it.layer == "SHORT_TERM" }.count)
                assertTrue(state.exchanges.single().status in setOf("completed", "cancelled"))
            } finally {
                controller.shutdown()
            }
        }
        assertIsolated({ error("fixture LLM failure") }, cancel = false)
        assertIsolated({ awaitCancellation() }, cancel = true)
    }

    private fun controller(
        gateway: McpGateway,
        answer: suspend (String, List<LlmMessage>, CompletionOptions) -> CompletionResult,
    ) = WorkbenchController(
        initialSettings = AppSettings(LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
        initialApiKeys = mapOf(LlmKind.OPENAI to "fixture-key"),
        assistantMemoryStore = InMemoryAssistantMemoryStore(),
        mcpGateway = gateway,
        clientFactory = { _, _, model -> object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
                answer(model, messages, options)
        } },
    )
}

private object StaticDigestGateway : McpGateway {
    override suspend fun listTools() = listOf(McpTool(
        GIT_ACTIVITY_REPORT_TOOL, "digest", buildJsonObject {}, "workspace",
    ))
    override suspend fun callTool(name: String, arguments: JsonObject) = McpToolResult(false, API_DIGEST_REPORT)
    override suspend fun close() = Unit
}

private const val API_DIGEST_REPORT = """{"schemaVersion":1,"generatedAt":"2026-10-15T10:00:00Z","since":"2026-10-14T10:00:00Z","branch":"feature/digest","detached":false,"head":"abcdef123456","commits":[{"sourceId":"C1","sha":"abcdef123456","authoredAt":"2026-10-15T09:00:00Z","subject":"Добавлена сводка"}],"files":[{"sourceId":"F1","path":"src/main/kotlin/Digest.kt","status":"added","scope":"committed","insertions":42,"deletions":0},{"sourceId":"F2","path":"frontend/src/Digest.tsx","status":"unstaged","scope":"working_tree","insertions":3,"deletions":1}],"workingTree":{"dirty":true,"stagedCount":0,"unstagedCount":1,"untrackedCount":0},"truncated":false}"""

private const val API_DIGEST_ANSWER = """## Коротко

Есть один коммит и локальное изменение.

## Что изменилось

- Добавлена реализация сводки [C1] [F1]

## Риски и проверки

- Тесты не подтверждены, есть локальное изменение [F2]

## Следующие шаги

- Проверить изменение и запустить тесты.

## Источники

- [C1] `abcdef123456` — `Добавлена сводка`
- [F1] `src/main/kotlin/Digest.kt` — `added`
- [F2] `frontend/src/Digest.tsx` — `unstaged`"""
