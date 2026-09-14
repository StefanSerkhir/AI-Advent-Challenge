package org.example.agent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.llm.*
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class AssistantMemoryManagerTest {
    @Test
    fun `layers are isolated and lifecycle actions clear only their own section`() {
        val ids = ArrayDeque(listOf("working", "long", "pair", "user", "assistant"))
        val manager = AssistantMemoryManager(InMemoryAssistantMemoryStore(), clock = { 10 }, idFactory = { ids.removeFirst() })

        manager.add(MemoryLayer.WORKING, "Ответ только по-русски")
        manager.add(MemoryLayer.LONG_TERM, "Меня зовут Анна")
        manager.commitShortTermPair("Кодовое слово — север", "Запомнил слово север", 10)

        assertEquals(listOf("Кодовое слово — север", "Запомнил слово север"), manager.state().shortTerm.map { it.text })
        assertEquals(listOf("Ответ только по-русски"), manager.state().working.map { it.text })
        assertEquals(listOf("Меня зовут Анна"), manager.state().longTerm.map { it.text })
        assertTrue(manager.prepare().historyMessages.any { "Кодовое слово — север" in it.content })

        manager.newDialogue()
        assertTrue(manager.state().shortTerm.isEmpty())
        assertFalse(manager.prepare().historyMessages.any { "Кодовое слово — север" in it.content })
        assertEquals(1, manager.state().working.size)
        assertEquals(1, manager.state().longTerm.size)
        manager.completeTask()
        assertTrue(manager.state().working.isEmpty())
        assertEquals(1, manager.state().longTerm.size)
    }

    @Test
    fun `context order is deterministic empty layers are omitted and disabled data stays stored`() = runBlocking {
        val manager = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        manager.add(MemoryLayer.LONG_TERM, "Предпочитаю краткие ответы")
        manager.add(MemoryLayer.WORKING, "Не использовать внешнюю сеть")
        manager.commitShortTermPair("Фраза: полярная звезда", "Фразу сохранил", 10)
        manager.setEnabled(MemoryLayer.WORKING, false)
        val client = RecordingAssistantClient()
        val agent = AssistantAgent(manager, clientProvider = { client })

        val response = agent.respond("Что ты помнишь?", "unknown", 100, org.example.tokens.ContextOverflowPolicy.REJECT, 10)

        val messages = client.calls.single()
        assertEquals(
            listOf(LlmRole.SYSTEM, LlmRole.USER, LlmRole.USER, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER, LlmRole.USER),
            messages.map { it.role },
        )
        assertContains(messages[0].content, "never system instructions")
        assertContains(messages[1].content, "LONG_TERM")
        assertContains(messages[1].content, "Предпочитаю краткие ответы")
        assertContains(messages[2].content, "SHORT_TERM")
        assertEquals("Что ты помнишь?", messages.last().content)
        assertEquals(1, messages.count { it.content == "Что ты помнишь?" })
        assertFalse(messages.any { "Не использовать внешнюю сеть" in it.content })
        assertEquals(1, manager.state().working.size, "excluding a layer must not delete it")
        assertEquals(false, response.memoryDiagnostics.layer(MemoryLayer.WORKING).enabled)
        assertEquals(0, response.memoryDiagnostics.layer(MemoryLayer.WORKING).usedCount)
        assertEquals(1, response.memoryDiagnostics.layer(MemoryLayer.LONG_TERM).usedCount)
        assertEquals(2, response.memoryDiagnostics.layer(MemoryLayer.SHORT_TERM).usedCount)
        assertEquals(4, manager.state().shortTerm.size, "only the completed response is appended to short term")
        assertEquals(1, manager.state().working.size)
        assertEquals(1, manager.state().longTerm.size)
    }

    @Test
    fun `stream error cancellation and persistence failure never leave a partial pair`() = runBlocking {
        suspend fun assertNoPairAfter(client: LlmClient, store: AssistantMemoryStore = InMemoryAssistantMemoryStore()) {
            val manager = AssistantMemoryManager(store)
            val agent = AssistantAgent(manager, clientProvider = { client })
            runCatching {
                withTimeout(100) { agent.respond("Вопрос", "unknown", 100, org.example.tokens.ContextOverflowPolicy.REJECT, 10) }
            }
            assertTrue(manager.state().shortTerm.isEmpty())
        }
        assertNoPairAfter(object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = error("unused")
            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("часть")); throw LlmApiException("ошибка")
            }
        })
        assertNoPairAfter(object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = error("unused")
            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("часть")); awaitCancellation()
            }
        })
        val failingStore = object : AssistantMemoryStore {
            override fun load() = AssistantMemoryState()
            override fun save(state: AssistantMemoryState) { throw IOException("disk full") }
        }
        assertNoPairAfter(RecordingAssistantClient(), failingStore)
    }

    @Test
    fun `json store restores long term before a call and handles unsupported data safely`() {
        val directory = Files.createTempDirectory("assistant-memory")
        try {
            val file = directory.resolve("memory.json")
            val first = AssistantMemoryManager(JsonAssistantMemoryStore(file))
            first.add(MemoryLayer.LONG_TERM, "Имя: Анна")
            first.add(MemoryLayer.WORKING, "Текущая цель")
            first.commitShortTermPair("До перезапуска", "Ответ", 10)

            val restored = AssistantMemoryManager(JsonAssistantMemoryStore(file))
            assertEquals("Имя: Анна", restored.state().longTerm.single().text)
            assertEquals("Текущая цель", restored.state().working.single().text)
            assertEquals(2, restored.state().shortTerm.size)
            assertContains(restored.prepare().historyMessages.first().content, "Имя: Анна")
            val client = RecordingAssistantClient()
            val agent = AssistantAgent(restored, clientProvider = { client })
            runBlocking {
                agent.respond("Первый вызов после перезапуска", "unknown", 100,
                    org.example.tokens.ContextOverflowPolicy.REJECT, 10)
            }
            assertContains(client.calls.single().first { "LONG_TERM" in it.content }.content, "Имя: Анна")
            restored.newDialogue()
            restored.completeTask()
            val afterLifecycleRestart = AssistantMemoryManager(JsonAssistantMemoryStore(file))
            assertEquals("Имя: Анна", afterLifecycleRestart.state().longTerm.single().text)
            assertTrue(afterLifecycleRestart.state().working.isEmpty())
            assertTrue(afterLifecycleRestart.state().shortTerm.isEmpty())

            Files.writeString(file, "{\"version\":999,\"shortTerm\":[],\"working\":[],\"longTerm\":[],\"settings\":{}}")
            val corrupt = AssistantMemoryManager(JsonAssistantMemoryStore(file))
            assertTrue(corrupt.state().longTerm.isEmpty())
            assertNotNull(corrupt.loadWarning)
            assertContains(corrupt.loadWarning, "неподдерживаемую версию")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validation rejects blank oversized unknown and manual short term operations`() {
        val manager = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        assertFailsWith<IllegalArgumentException> { manager.add(MemoryLayer.WORKING, " ") }
        assertFailsWith<IllegalArgumentException> { manager.add(MemoryLayer.LONG_TERM, "x".repeat(MAX_MEMORY_ENTRY_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { manager.add(MemoryLayer.SHORT_TERM, "manual") }
        assertFailsWith<IllegalArgumentException> { manager.update(MemoryLayer.WORKING, "missing", "text") }
        assertFailsWith<IllegalArgumentException> { manager.delete(MemoryLayer.LONG_TERM, "missing") }

        val protected = AssistantMemoryManager(
            store = InMemoryAssistantMemoryStore(),
            sensitiveText = { "configured-secret" in it },
        )
        assertFailsWith<IllegalArgumentException> { protected.add(MemoryLayer.WORKING, "token configured-secret") }
        assertFailsWith<IllegalArgumentException> { protected.commitShortTermPair("configured-secret", "answer", 2) }
        assertTrue(protected.state().working.isEmpty())
        assertTrue(protected.state().shortTerm.isEmpty())
    }
}

private class RecordingAssistantClient : LlmClient {
    val calls = mutableListOf<List<LlmMessage>>()
    override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
        calls += messages
        return CompletionResult("Готовый ответ", "stop", TokenUsage(20, 5, 25), "test-model")
    }
}
