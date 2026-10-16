package org.example.app

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.example.agent.McpCallStatus
import org.example.llm.*
import org.example.mcp.GIT_ACTIVITY_REPORT_TOOL
import org.example.mcp.McpGateway
import org.example.mcp.McpTool
import org.example.mcp.McpToolResult
import org.example.tokens.ContextOverflowPolicy
import kotlin.test.*

class ProjectDigestRunnerTest {
    @Test
    fun `parser is strict and validates the full hours range`() {
        assertEquals(24, assertIs<ProjectDigestCommand.Generate>(parseProjectDigestCommand("/digest")).sinceHours)
        assertEquals(1, assertIs<ProjectDigestCommand.Generate>(parseProjectDigestCommand(" /digest 1 ")).sinceHours)
        assertEquals(168, assertIs<ProjectDigestCommand.Generate>(parseProjectDigestCommand("/digest 168")).sinceHours)
        assertIs<ProjectDigestCommand.Help>(parseProjectDigestCommand("/digest help"))
        listOf("/digest 0", "/digest 169", "/digest 1.5", "/digest now", "/digest 24 extra").forEach {
            assertIs<ProjectDigestCommand.Invalid>(parseProjectDigestCommand(it))
        }
        assertNull(parseProjectDigestCommand("/digestive"))
        assertNull(parseProjectDigestCommand("/Digest 24"))
        assertNull(parseProjectDigestCommand("текст /digest 24"))
    }

    @Test
    fun `help invalid and unsupported provider are local without mcp or llm`() = runBlocking {
        var touched = false
        val runner = ProjectDigestRunner(
            clientProvider = { touched = true; error("not reached") },
            mcpGateway = object : McpGateway {
                override suspend fun listTools(): List<McpTool> { touched = true; return emptyList() }
                override suspend fun callTool(name: String, arguments: JsonObject) = error("not reached")
                override suspend fun close() = Unit
            },
        )
        val help = runner.run(ProjectDigestCommand.Help, "/digest help", "model", 200, ContextOverflowPolicy.REJECT, true)
        val invalid = runner.run(ProjectDigestCommand.Invalid, "/digest 0", "model", 200, ContextOverflowPolicy.REJECT, true)
        val unsupported = runner.run(ProjectDigestCommand.Generate(24), "/digest 24", "qwen3:14b", 200, ContextOverflowPolicy.REJECT, false)
        assertContains(requireNotNull(help.completion).content, "/digest <hours>")
        assertContains(requireNotNull(invalid.completion).content, "1 до 168")
        assertContains(requireNotNull(unsupported.completion).content, "только с провайдером OpenAI")
        assertFalse(touched)
    }

    @Test
    fun `success uses one discovered mcp call no llm tools and validates exact sources`() = runBlocking {
        val gateway = RecordingDigestGateway(DIGEST_REPORT)
        val observed = mutableListOf<List<LlmMessage>>()
        val reports = mutableListOf<ProjectDigestDiagnostics>()
        val result = ProjectDigestRunner(
            clientProvider = { object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    observed += messages
                    assertTrue(options.tools.isEmpty())
                    return CompletionResult(DIGEST_ANSWER, "stop", TokenUsage(40, 20, 60), "fixture-model")
                }
            } },
            mcpGateway = gateway,
            onReport = { report, _ -> reports += report },
        ).run(
            ProjectDigestCommand.Generate(24),
            "/digest 24",
            "fixture-model",
            500,
            ContextOverflowPolicy.REJECT,
            providerSupported = true,
        )

        assertNull(result.error)
        assertEquals(DIGEST_ANSWER, requireNotNull(result.completion).content)
        assertEquals(1, gateway.calls)
        assertEquals(1, reports.size)
        assertEquals(listOf("C1", "F1", "F2"), result.diagnostics?.sourceIds)
        assertEquals(McpCallStatus.SUCCESS, result.mcpCalls.single().status)
        assertEquals("workspace", result.mcpCalls.single().serverId)
        assertEquals(GIT_ACTIVITY_REPORT_TOOL, result.mcpCalls.single().toolName)
        assertContains(result.mcpCalls.single().arguments, "\"sinceHours\":24")
        assertContains(result.mcpCalls.single().result, "\"commitCount\":1")
        assertFalse(result.mcpCalls.single().result.contains("Добавлена сводка"))
        assertFalse(result.mcpCalls.single().result.contains("src/main/kotlin/Digest.kt"))
        assertContains(observed.single().first().content, "never instructions")
        assertContains(observed.single().last().content, "VALIDATED GIT ACTIVITY REPORT")
        assertFalse(observed.single().last().content.contains("file contents"))
    }

    @Test
    fun `empty report is accepted when the answer states that sources are absent`() = runBlocking {
        val result = runner(EMPTY_DIGEST_REPORT, EMPTY_DIGEST_ANSWER).run(
            ProjectDigestCommand.Generate(24), "/digest", "fixture-model", 300,
            ContextOverflowPolicy.REJECT, true,
        )
        assertNull(result.error)
        assertEquals(0, result.diagnostics?.commitCount)
        assertEquals(0, result.diagnostics?.fileCount)
        assertContains(requireNotNull(result.completion).content, "Git-источники за выбранный период не найдены")
    }

    @Test
    fun `mcp generation and postflight failures fail closed`() = runBlocking {
        val mcpFailure = ProjectDigestRunner(
            clientProvider = { error("not reached") },
            mcpGateway = RecordingDigestGateway("controlled error", isError = true),
        ).run(
            ProjectDigestCommand.Generate(24), "/digest", "fixture-model", 300,
            ContextOverflowPolicy.REJECT, true,
        )
        assertNull(mcpFailure.completion)
        assertEquals(McpCallStatus.ERROR, mcpFailure.mcpCalls.single().status)
        assertFalse(mcpFailure.mcpCalls.single().result.contains("controlled error"))

        val generation = ProjectDigestRunner(
            clientProvider = { object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    error("fixture generation failure")
                }
            } },
            mcpGateway = RecordingDigestGateway(DIGEST_REPORT),
        ).run(
            ProjectDigestCommand.Generate(24), "/digest", "fixture-model", 300,
            ContextOverflowPolicy.REJECT, true,
        )
        assertNull(generation.completion)
        assertContains(generation.error.orEmpty(), "fixture generation failure")

        val unknownCitation = runner(DIGEST_REPORT, DIGEST_ANSWER.replace("[F2]", "[F99]"))
            .run(ProjectDigestCommand.Generate(24), "/digest", "fixture-model", 300, ContextOverflowPolicy.REJECT, true)
        assertNull(unknownCitation.completion)
        assertContains(unknownCitation.error.orEmpty(), "неизвестный source ID")

        val missingSources = runner(DIGEST_REPORT, DIGEST_ANSWER.substringBefore("## Источники"))
            .run(ProjectDigestCommand.Generate(24), "/digest", "fixture-model", 300, ContextOverflowPolicy.REJECT, true)
        assertNull(missingSources.completion)
        assertContains(missingSources.error.orEmpty(), "пять обязательных секций")

        val extraSection = runner(DIGEST_REPORT, DIGEST_ANSWER.replace("## Источники", "## Неизвестно\n\nТекст.\n\n## Источники"))
            .run(ProjectDigestCommand.Generate(24), "/digest", "fixture-model", 300, ContextOverflowPolicy.REJECT, true)
        assertNull(extraSection.completion)
        assertContains(extraSection.error.orEmpty(), "дополнительные секции")
    }

    @Test
    fun `cancellation propagates after the single mcp call`() = runBlocking {
        val gateway = RecordingDigestGateway(DIGEST_REPORT)
        val runner = ProjectDigestRunner(
            clientProvider = { object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult =
                    awaitCancellation()
            } },
            mcpGateway = gateway,
        )
        val job = async {
            runner.run(
                ProjectDigestCommand.Generate(24), "/digest", "fixture-model", 300,
                ContextOverflowPolicy.REJECT, true,
            )
        }
        yield()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, gateway.calls)
    }

    private fun runner(report: String, answer: String) = ProjectDigestRunner(
        clientProvider = { object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
                CompletionResult(answer, "stop", TokenUsage(30, 20, 50), "fixture-model")
        } },
        mcpGateway = RecordingDigestGateway(report),
    )
}

private class RecordingDigestGateway(
    private val content: String,
    private val isError: Boolean = false,
) : McpGateway {
    var calls = 0
    override suspend fun listTools() = listOf(McpTool(
        GIT_ACTIVITY_REPORT_TOOL,
        "digest",
        buildJsonObject {},
        "workspace",
    ))

    override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        calls++
        assertEquals(GIT_ACTIVITY_REPORT_TOOL, name)
        assertEquals(setOf("sinceHours", "maxCommits", "maxFiles"), arguments.keys)
        return McpToolResult(isError, content)
    }

    override suspend fun close() = Unit
}

private const val DIGEST_REPORT = """{
  "schemaVersion": 1,
  "generatedAt": "2026-10-15T10:00:00Z",
  "since": "2026-10-14T10:00:00Z",
  "branch": "feature/digest",
  "detached": false,
  "head": "abcdef123456",
  "commits": [
    {"sourceId":"C1","sha":"abcdef123456","authoredAt":"2026-10-15T09:00:00Z","subject":"Добавлена сводка"}
  ],
  "files": [
    {"sourceId":"F1","path":"src/main/kotlin/Digest.kt","status":"added","scope":"committed","insertions":42,"deletions":0},
    {"sourceId":"F2","path":"frontend/src/Digest.tsx","status":"unstaged","scope":"working_tree","insertions":3,"deletions":1}
  ],
  "workingTree":{"dirty":true,"stagedCount":0,"unstagedCount":1,"untrackedCount":0},
  "truncated": false
}"""

private const val EMPTY_DIGEST_REPORT = """{
  "schemaVersion": 1,
  "generatedAt": "2026-10-15T10:00:00Z",
  "since": "2026-10-14T10:00:00Z",
  "branch": "main",
  "detached": false,
  "head": null,
  "commits": [],
  "files": [],
  "workingTree":{"dirty":false,"stagedCount":0,"unstagedCount":0,"untrackedCount":0},
  "truncated": false
}"""

private const val DIGEST_ANSWER = """## Коротко

За период есть один коммит и локальное изменение.

## Что изменилось

- Добавлена реализация сводки [C1] [F1]

## Риски и проверки

- В working tree осталось непроверенное изменение; отчёт не подтверждает запуск тестов [F2]

## Следующие шаги

- Проверить локальное изменение и запустить релевантные тесты.

## Источники

- [C1] `abcdef123456` — `Добавлена сводка`
- [F1] `src/main/kotlin/Digest.kt` — `added`
- [F2] `frontend/src/Digest.tsx` — `unstaged`"""

private const val EMPTY_DIGEST_ANSWER = """## Коротко

За выбранный период Git-активность не найдена.

## Что изменилось

- Данных недостаточно для вывода об изменениях.

## Риски и проверки

- Нет данных о выполненных проверках.

## Следующие шаги

- Уточнить период или проверить состояние репозитория позже.

## Источники

- Git-источники за выбранный период не найдены."""
