package org.example.app

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.example.indexing.*
import org.example.llm.*
import org.example.mcp.GIT_CURRENT_BRANCH_TOOL
import org.example.mcp.McpGateway
import org.example.mcp.McpTool
import org.example.mcp.McpToolResult
import org.example.tokens.ContextOverflowPolicy
import java.nio.file.Files
import kotlin.test.*

class DeveloperHelpRunnerTest {
    @Test
    fun `parser recognizes only the exact help command token`() {
        assertIs<DeveloperHelpCommand.Overview>(parseDeveloperHelpCommand("/help"))
        assertIs<DeveloperHelpCommand.Overview>(parseDeveloperHelpCommand("  /help  "))
        assertEquals(
            "Как устроен проект?",
            assertIs<DeveloperHelpCommand.Question>(parseDeveloperHelpCommand("/help Как устроен проект?")).question,
        )
        assertNull(parseDeveloperHelpCommand("/helpful"))
        assertNull(parseDeveloperHelpCommand("текст /help в середине"))
        assertNull(parseDeveloperHelpCommand("/Help вопрос"))
    }

    @Test
    fun `overview is local and does not touch retrieval mcp or llm`() = runBlocking {
        var touched = false
        val result = DeveloperHelpRunner(
            clientProvider = { touched = true; error("not reached") },
            retrieverProvider = { touched = true; error("not reached") },
            mcpGateway = object : McpGateway {
                override suspend fun listTools(): List<McpTool> { touched = true; return emptyList() }
                override suspend fun callTool(name: String, arguments: JsonObject) = error("not reached")
                override suspend fun close() = Unit
            },
        ).run(
            DeveloperHelpCommand.Overview,
            "/help",
            "model",
            200,
            ContextOverflowPolicy.REJECT,
            10,
            5,
            0.2,
            "openai",
            mcpEnabled = true,
        )

        assertFalse(touched)
        assertContains(requireNotNull(result.completion).content, "/help <вопрос>")
        assertNull(result.ragDiagnostics)
        assertTrue(result.mcpCalls.isEmpty())
    }

    @Test
    fun `question retrieves only repository docs calls git through discovered owner and validates evidence`() = runBlocking {
        val directory = Files.createTempDirectory("developer-help")
        try {
            val index = createRagFixtureIndex(directory)
            val gateway = RecordingGitGateway()
            val calls = mutableListOf<List<LlmMessage>>()
            val client = object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    calls += messages
                    return CompletionResult(
                        groundedAssistantRagFixtureAnswer(
                            messages.last().content,
                            "Архитектура подтверждена документацией [S1]. Текущая Git-ветка: `feature/help`.",
                        ),
                        "stop",
                        TokenUsage(40, 20, 60),
                        "fixture-model",
                    )
                }
            }
            val result = runner(index, client, gateway).run(
                DeveloperHelpCommand.Question("Как устроен проект и какая сейчас Git-ветка?"),
                "/help Как устроен проект и какая сейчас Git-ветка?",
                "fixture-model",
                300,
                ContextOverflowPolicy.REJECT,
                10,
                5,
                -1.0,
                "openai",
                mcpEnabled = true,
            )

            assertNull(result.error)
            assertContains(requireNotNull(result.completion).content, "feature/help")
            assertEquals(1, gateway.calls)
            assertEquals("{}", result.mcpCalls.single().arguments)
            assertEquals("workspace", result.mcpCalls.single().serverId)
            assertEquals(GIT_CURRENT_BRANCH_TOOL, result.mcpCalls.single().toolName)
            assertEquals(RagEvidenceStatus.VERIFIED, result.ragDiagnostics?.evidence?.status)
            assertTrue(result.ragDiagnostics?.sources.orEmpty().all {
                it.source == "README.md" || it.source.startsWith("docs/")
            })
            assertTrue(calls.single().last().content.contains("WORKSPACE MCP DATA"))
            assertTrue(calls.single().last().content.contains("\"branch\":\"feature/help\""))
            assertEquals(1, calls.single().last().content.windowed("Как устроен проект и какая сейчас Git-ветка?".length)
                .count { it == "Как устроен проект и какая сейчас Git-ветка?" })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mcp failure is localized and does not hide retrieved documentation`() = runBlocking {
        val directory = Files.createTempDirectory("developer-help-mcp-error")
        try {
            val index = createRagFixtureIndex(directory)
            val gateway = RecordingGitGateway(McpToolResult(true, "controlled git failure"))
            val client = object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = CompletionResult(
                    groundedAssistantRagFixtureAnswer(messages.last().content, "Документация всё равно доступна [S1]. Git-контекст недоступен."),
                    "stop", TokenUsage(20, 10, 30), "fixture-model",
                )
            }
            val result = runner(index, client, gateway).run(
                DeveloperHelpCommand.Question("Как устроен проект?"),
                "/help Как устроен проект?",
                "fixture-model", 200, ContextOverflowPolicy.REJECT,
                10, 5, -1.0, "openai", mcpEnabled = true,
            )

            assertNull(result.error)
            assertContains(requireNotNull(result.completion).content, "Документация")
            assertEquals(org.example.agent.McpCallStatus.ERROR, result.mcpCalls.single().status)
            assertFalse(result.mcpCalls.single().result.contains("controlled git failure"))
            assertEquals(RagEvidenceStatus.VERIFIED, result.ragDiagnostics?.evidence?.status)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generation failure returns diagnostics without a completion`() = runBlocking {
        val directory = Files.createTempDirectory("developer-help-generation-error")
        try {
            val index = createRagFixtureIndex(directory)
            val result = runner(index, object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    throw IllegalStateException("controlled generation failure")
                }
            }, RecordingGitGateway()).run(
                DeveloperHelpCommand.Question("Как устроен проект?"),
                "/help Как устроен проект?",
                "fixture-model", 200, ContextOverflowPolicy.REJECT,
                10, 5, -1.0, "openai", mcpEnabled = true,
            )

            assertNull(result.completion)
            assertContains(result.error.orEmpty(), "controlled generation failure")
            assertEquals(DEVELOPER_HELP_PIPELINE, result.ragDiagnostics?.pipeline)
            assertEquals(GIT_CURRENT_BRANCH_TOOL, result.mcpCalls.single().toolName)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unexpected git payload cannot expose an absolute path`() = runBlocking {
        val directory = Files.createTempDirectory("developer-help-git-payload")
        try {
            val index = createRagFixtureIndex(directory)
            val privatePath = "/Users/private/repository"
            val client = object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    assertFalse(messages.last().content.contains(privatePath))
                    return CompletionResult(
                        groundedAssistantRagFixtureAnswer(
                            messages.last().content,
                            "Документация доступна [S1], Git-контекст недоступен.",
                        ),
                        "stop", TokenUsage(20, 10, 30), "fixture-model",
                    )
                }
            }
            val result = runner(
                index,
                client,
                RecordingGitGateway(McpToolResult(
                    false,
                    "{\"available\":true,\"branch\":\"$privatePath\",\"detached\":false}",
                )),
            ).run(
                DeveloperHelpCommand.Question("Как устроен проект?"),
                "/help Как устроен проект?", "fixture-model", 200,
                ContextOverflowPolicy.REJECT, 10, 5, -1.0, "openai", mcpEnabled = true,
            )

            assertNull(result.error)
            assertEquals(org.example.agent.McpCallStatus.ERROR, result.mcpCalls.single().status)
            assertFalse(result.mcpCalls.single().result.contains(privatePath))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `generation cancellation propagates`() = runBlocking {
        val directory = Files.createTempDirectory("developer-help-generation-cancel")
        try {
            val index = createRagFixtureIndex(directory)
            val runner = runner(index, object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult =
                    awaitCancellation()
            }, RecordingGitGateway())
            val job = async {
                runner.run(
                    DeveloperHelpCommand.Question("Как устроен проект?"),
                    "/help Как устроен проект?", "fixture-model", 200,
                    ContextOverflowPolicy.REJECT, 10, 5, -1.0, "openai", mcpEnabled = true,
                )
            }
            yield()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing index returns exact safe build command without llm or mcp`() = runBlocking {
        val directory = Files.createTempDirectory("developer-help-missing-index")
        try {
            var llmCalled = false
            val gateway = RecordingGitGateway()
            val result = DeveloperHelpRunner(
                clientProvider = { llmCalled = true; error("not reached") },
                retrieverProvider = {
                    DocumentRetriever(JsonDocumentIndexStore(directory.resolve("missing.json"))) {
                        error("not reached")
                    }
                },
                mcpGateway = gateway,
            ).run(
                DeveloperHelpCommand.Question("Вопрос"), "/help Вопрос", "model", 100,
                ContextOverflowPolicy.REJECT, 10, 5, 0.2, "openai", mcpEnabled = true,
            )

            assertContains(result.error.orEmpty(), "./gradlew buildLocalDocumentIndex")
            assertFalse(llmCalled)
            assertEquals(0, gateway.calls)
            assertTrue(result.mcpCalls.isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `retrieval cancellation propagates`() = runBlocking {
        val directory = Files.createTempDirectory("developer-help-cancel")
        try {
            val index = createRagFixtureIndex(directory)
            val runner = DeveloperHelpRunner(
                clientProvider = { error("not reached") },
                retrieverProvider = {
                    DocumentRetriever(JsonDocumentIndexStore(index)) { descriptor ->
                        object : org.example.indexing.EmbeddingClient {
                            override val provider = descriptor.provider
                            override val model = descriptor.model
                            override suspend fun embed(texts: List<String>) = awaitCancellation()
                        }
                    }
                },
                mcpGateway = RecordingGitGateway(),
            )
            val job = async {
                runner.run(
                    DeveloperHelpCommand.Question("Вопрос"), "/help Вопрос", "model", 100,
                    ContextOverflowPolicy.REJECT, 10, 5, 0.2, "openai", mcpEnabled = true,
                )
            }
            yield()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun runner(index: java.nio.file.Path, client: LlmClient, gateway: McpGateway) = DeveloperHelpRunner(
        clientProvider = { client },
        retrieverProvider = {
            DocumentRetriever(JsonDocumentIndexStore(index)) { descriptor ->
                DeterministicFakeEmbeddingClient(descriptor.dimensions, descriptor.model)
            }
        },
        mcpGateway = gateway,
    )
}

private class RecordingGitGateway(
    private val result: McpToolResult = McpToolResult(
        false,
        "{\"available\":true,\"branch\":\"feature/help\",\"detached\":false,\"shortCommit\":\"abcdef123456\"}",
    ),
) : McpGateway {
    var calls = 0
    override suspend fun listTools() = listOf(McpTool(
        GIT_CURRENT_BRANCH_TOOL,
        "branch",
        buildJsonObject {},
        "workspace",
    ))
    override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        calls++
        assertEquals(GIT_CURRENT_BRANCH_TOOL, name)
        assertTrue(arguments.isEmpty())
        return result
    }
    override suspend fun close() = Unit
}
