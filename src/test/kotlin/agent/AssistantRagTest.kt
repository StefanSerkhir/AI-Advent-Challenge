package org.example.agent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.app.RagEvidenceStatus
import org.example.indexing.ChunkingKind
import org.example.indexing.DocumentRetrievalResult
import org.example.indexing.RetrievedDocumentChunk
import org.example.llm.*
import org.example.tokens.ContextOverflowPolicy
import java.io.IOException
import kotlin.test.*

class AssistantRagTest {
    @Test
    fun `contextual retrieval runs on every turn and resolves follow ups from task working and short term`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        memory.add(MemoryLayer.WORKING, "Ограничение: использовать только JDK 21")
        memory.add(MemoryLayer.WORKING, "Термин: быстрый запуск означает команду Gradle")
        memory.commitShortTermPair("Сначала опиши запуск", "Первый вариант уже описан", 6)
        val tasks = TaskStateManager(InMemoryTaskStateStore())
        tasks.start(NewTaskDraft("Подготовить инструкцию запуска", "Сравнить варианты", "Выбрать второй вариант"))
        val queries = mutableListOf<String>()
        val client = GroundedAssistantClient(validAnswer())
        val agent = AssistantAgent(
            memory,
            taskStateProvider = tasks::state,
            ragRetrieve = { query, limit ->
                queries += query
                assertEquals(7, limit)
                retrieval(score = 0.91)
            },
            clientProvider = { client },
        )

        val first = agent.respond(
            "Продолжай",
            "test-model",
            200,
            ContextOverflowPolicy.REJECT,
            6,
            ragEnabled = true,
            ragCandidateLimit = 7,
            ragResultLimit = 3,
            ragMinSimilarity = 0.5,
        )
        val second = agent.respond(
            "А второй вариант?",
            "test-model",
            200,
            ContextOverflowPolicy.REJECT,
            6,
            ragEnabled = true,
            ragCandidateLimit = 7,
            ragResultLimit = 3,
            ragMinSimilarity = 0.5,
        )

        assertEquals(2, queries.size)
        assertContains(queries[0], "Текущий вопрос: Продолжай")
        assertContains(queries[0], "цель=Подготовить инструкцию запуска")
        assertContains(queries[0], "Ограничение: использовать только JDK 21")
        assertContains(queries[0], "Термин: быстрый запуск")
        assertContains(queries[0], "Первый вариант уже описан")
        assertContains(queries[1], "Текущий вопрос: А второй вариант?")
        assertContains(queries[1], "Подтверждённый ответ")
        assertTrue(queries.all { it.length <= MAX_ASSISTANT_RAG_QUERY_CHARACTERS })
        assertEquals(RagEvidenceStatus.VERIFIED, first.ragDiagnostics?.evidence?.status)
        assertEquals("README.md", first.ragDiagnostics?.evidence?.sources?.single()?.source)
        assertEquals(2, client.calls.size)
        assertTrue(client.calls.all { call -> call.last().content.countOccurrences("Продолжай") <= 1 })
        assertEquals(6, memory.state().shortTerm.size, "лимит хранит только полные успешные пары")
        assertEquals("Подтверждённый ответ [S1].", second.completion.content)
    }

    @Test
    fun `disabled layer is excluded from retrieval query but remains stored`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        memory.add(MemoryLayer.WORKING, "Ограничение: секрет рабочей задачи")
        memory.setEnabled(MemoryLayer.WORKING, false)
        var query = ""
        val agent = AssistantAgent(
            memory,
            ragRetrieve = { value, _ -> query = value; retrieval() },
            clientProvider = { GroundedAssistantClient(validAnswer()) },
        )

        agent.respond("Продолжай", "test-model", 200, ContextOverflowPolicy.REJECT, 4, ragEnabled = true)

        assertFalse("секрет рабочей задачи" in query)
        assertEquals(1, memory.state().working.size)
    }

    @Test
    fun `contextual retrieval query is hard bounded for oversized memory`() {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        memory.add(MemoryLayer.WORKING, "W".repeat(MAX_MEMORY_ENTRY_LENGTH))
        repeat(3) { index ->
            memory.commitShortTermPair(
                "U$index" + "u".repeat(MAX_MEMORY_ENTRY_LENGTH - 2),
                "A$index" + "a".repeat(MAX_MEMORY_ENTRY_LENGTH - 2),
                6,
            )
        }

        val query = buildAssistantRagRetrievalQuery(
            currentPrompt = "P".repeat(MAX_MEMORY_ENTRY_LENGTH),
            activeTask = null,
            memory = memory.prepare(6),
        )

        assertEquals(MAX_ASSISTANT_RAG_QUERY_CHARACTERS, query.length)
        assertTrue(query.startsWith("Текущий вопрос: "))
        assertContains(query, "Рабочая память текущей задачи:")
        assertContains(query, "Недавний успешный ход")

        val unicodeBoundary = buildAssistantRagRetrievalQuery(
            currentPrompt = "P".repeat(1_399) + "😀",
            activeTask = null,
            memory = AssistantMemoryManager(InMemoryAssistantMemoryStore()).prepare(),
        )
        assertFalse(unicodeBoundary.any { it.code in 0xD800..0xDFFF })
    }

    @Test
    fun `below threshold is a completed abstention without generation`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        val client = GroundedAssistantClient(validAnswer())
        val agent = AssistantAgent(
            memory,
            ragRetrieve = { _, _ -> retrieval(score = 0.1) },
            clientProvider = { client },
        )

        val response = agent.respond(
            "Неизвестный вопрос",
            "test-model",
            200,
            ContextOverflowPolicy.REJECT,
            4,
            ragEnabled = true,
            ragMinSimilarity = 0.8,
        )

        assertTrue(response.ragDiagnostics?.abstained == true)
        assertEquals(RagEvidenceStatus.NOT_APPLICABLE, response.ragDiagnostics.evidence.status)
        assertContains(response.completion.content, "Не знаю")
        assertEquals(0, client.calls.size)
        assertEquals(2, memory.state().shortTerm.size)
    }

    @Test
    fun `missing or unknown citation fabricated quote metadata and missing sources fail closed`() = runBlocking {
        val invalidAnswers = listOf(
            validAnswer().replace("Подтверждённый ответ [S1].", "Подтверждённый ответ."),
            validAnswer().replace("[S1]", "[S9]"),
            validAnswer().replace(FIXTURE_QUOTE, "Вымышленная цитата"),
            validAnswer().replace("Источники\n- [S1]", "Источники\n- [S1] README.md"),
            validAnswer().substringBefore("\n\nИсточники"),
        )
        invalidAnswers.forEach { answer ->
            val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
            val agent = AssistantAgent(
                memory,
                ragRetrieve = { _, _ -> retrieval() },
                clientProvider = { GroundedAssistantClient(answer) },
            )

            assertFailsWith<IllegalArgumentException> {
                agent.respond("Вопрос", "test-model", 200, ContextOverflowPolicy.REJECT, 4, ragEnabled = true)
            }
            assertTrue(memory.state().shortTerm.isEmpty())
        }
    }

    @Test
    fun `retrieval generation cancellation and persistence failures never commit short term`() = runBlocking {
        suspend fun assertEmpty(agent: AssistantAgent, memory: AssistantMemoryManager, timeout: Boolean = false) {
            runCatching {
                if (timeout) withTimeout(50) {
                    agent.respond("Вопрос", "test-model", 200, ContextOverflowPolicy.REJECT, 4, ragEnabled = true)
                } else {
                    agent.respond("Вопрос", "test-model", 200, ContextOverflowPolicy.REJECT, 4, ragEnabled = true)
                }
            }
            assertTrue(memory.state().shortTerm.isEmpty())
        }

        AssistantMemoryManager(InMemoryAssistantMemoryStore()).let { memory ->
            assertEmpty(AssistantAgent(
                memory,
                ragRetrieve = { _, _ -> throw IOException("retrieval failed") },
                clientProvider = { GroundedAssistantClient(validAnswer()) },
            ), memory)
        }
        AssistantMemoryManager(InMemoryAssistantMemoryStore()).let { memory ->
            assertEmpty(AssistantAgent(
                memory,
                ragRetrieve = { _, _ -> retrieval() },
                clientProvider = { GroundedAssistantClient(error = IOException("generation failed")) },
            ), memory)
        }
        AssistantMemoryManager(InMemoryAssistantMemoryStore()).let { memory ->
            assertEmpty(AssistantAgent(
                memory,
                ragRetrieve = { _, _ -> awaitCancellation() },
                clientProvider = { GroundedAssistantClient(validAnswer()) },
            ), memory, timeout = true)
        }
        val failingStore = object : AssistantMemoryStore {
            override fun load() = AssistantMemoryState()
            override fun save(state: AssistantMemoryState) = throw IOException("disk full")
        }
        AssistantMemoryManager(failingStore).let { memory ->
            assertEmpty(AssistantAgent(
                memory,
                ragRetrieve = { _, _ -> retrieval() },
                clientProvider = { GroundedAssistantClient(validAnswer()) },
            ), memory)
        }
    }
}

private const val FIXTURE_QUOTE = "Production web-приложение запускается командой ./gradlew runWeb."

private fun validAnswer() = """
    Ответ
    Подтверждённый ответ [S1].

    Цитаты
    - [S1] «$FIXTURE_QUOTE»

    Источники
    - [S1]
""".trimIndent()

private fun retrieval(score: Double = 0.9) = DocumentRetrievalResult(
    strategy = ChunkingKind.STRUCTURED,
    embeddingModel = "fixture-embedding",
    manifestHash = "fixture-manifest",
    chunks = listOf(RetrievedDocumentChunk(
        rank = 1,
        score = score,
        chunkId = "chunk-1",
        source = "README.md",
        title = "README",
        section = "Быстрый запуск",
        text = "$FIXTURE_QUOTE Требуются JDK 21+ и Node.js 22.12+.",
    )),
)

private class GroundedAssistantClient(
    private val answer: String? = null,
    private val error: Throwable? = null,
) : LlmClient {
    val calls = mutableListOf<List<LlmMessage>>()

    override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
        calls += messages
        error?.let { throw it }
        return CompletionResult(requireNotNull(answer), "stop", TokenUsage(40, 20, 60), "test-model")
    }
}

private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
