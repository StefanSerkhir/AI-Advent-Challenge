package org.example.agent

import kotlinx.coroutines.runBlocking
import org.example.llm.LlmMessage
import org.example.llm.LlmRole
import org.example.llm.TokenUsage
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ContextManagerTest {
    @Test fun `sliding window sends and persists exactly last N ordinary messages`() = runBlocking {
        val manager = manager()
        exchange(manager, ContextStrategy.SLIDING_WINDOW, 3, "u1", "a1")
        exchange(manager, ContextStrategy.SLIDING_WINDOW, 3, "u2", "a2")
        val prepared = manager.prepare("s", user("u3"), ContextConfig(ContextStrategy.SLIDING_WINDOW, 3, "sys"))
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER), prepared.messages.map { it.role })
        assertEquals(listOf("sys", "u2", "a2", "u3"), prepared.messages.map { it.content })
        manager.commit(prepared, assistant("a3"))
        assertEquals(listOf("a2", "u3", "a3"), manager.state("s").slidingMessages.map { it.content })
    }

    @Test fun `sticky facts survive raw window and support replace and delete`() = runBlocking {
        val patches = ArrayDeque(listOf(
            FactPatch(mapOf("name" to "Анна"), usage = TokenUsage(2, 1, 3)),
            FactPatch(mapOf("name" to "Мария")),
            FactPatch(delete = setOf("name")),
        ))
        val manager = ContextManager(InMemoryContextStateStore(), FactExtractor { _, _ -> patches.removeFirst() })
        exchange(manager, ContextStrategy.STICKY_FACTS, 1, "Меня зовут Анна", "assistant says city=Paris")
        assertEquals("Анна", manager.state("s").stickyFacts["name"])
        val replace = manager.prepare("s", user("Теперь Мария"), ContextConfig(ContextStrategy.STICKY_FACTS, 1, "sys"))
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.SYSTEM, LlmRole.USER), replace.messages.map { it.role })
        assertContains(replace.messages[1].content, "name: Мария")
        assertFalse(replace.messages[1].content.contains("Paris"))
        manager.commit(replace, assistant("ok"))
        assertEquals(mapOf("name" to "Мария"), manager.state("s").stickyFacts)
        val delete = manager.prepare("s", user("Забудь имя"), ContextConfig(ContextStrategy.STICKY_FACTS, 1))
        manager.commit(delete, assistant("ok"))
        assertTrue(manager.state("s").stickyFacts.isEmpty())
        assertEquals(3, manager.state("s").factUsage.requests)
    }

    @Test fun `fact candidate is not saved until successful commit`() = runBlocking {
        val manager = ContextManager(InMemoryContextStateStore(), FactExtractor { _, _ -> FactPatch(mapOf("goal" to "ship")) })
        manager.prepare("s", user("Моя цель ship"), ContextConfig(ContextStrategy.STICKY_FACTS, 2))
        assertTrue(manager.state("s").stickyFacts.isEmpty())
        assertTrue(manager.state("s").stickyMessages.isEmpty())
        assertEquals(1, manager.state("s").factUsage.requests)
    }

    @Test fun `branch checkpoint prefix is immutable and suffixes are independent`() = runBlocking {
        val ids = ArrayDeque(listOf("cp", "a", "b"))
        val manager = ContextManager(InMemoryContextStateStore(), FactExtractor { _, _ -> FactPatch() }, idFactory = { ids.removeFirst() })
        exchange(manager, ContextStrategy.BRANCHING, 10, "prefix", "prefix-answer")
        manager.createCheckpoint("s")
        exchange(manager, ContextStrategy.BRANCHING, 10, "only-a", "answer-a")
        manager.switchBranch("s", "b")
        val bRequest = manager.prepare("s", user("only-b"), ContextConfig(ContextStrategy.BRANCHING, 10))
        assertEquals(listOf("prefix", "prefix-answer", "only-b"), bRequest.messages.drop(1).map { it.content })
        manager.commit(bRequest, assistant("answer-b"))
        manager.switchBranch("s", "a")
        val restoredA = manager.prepare("s", user("back-a"), ContextConfig(ContextStrategy.BRANCHING, 10))
        assertEquals(listOf("prefix", "prefix-answer", "only-a", "answer-a", "back-a"), restoredA.messages.drop(1).map { it.content })
        assertFalse(restoredA.messages.any { it.content == "only-b" })
        assertEquals(listOf("prefix", "prefix-answer"), manager.state("s").branching.checkpoint!!.messages.map { it.content })
    }

    @Test fun `facts and branches restore from versioned JSON and clear removes all strategies`() = runBlocking {
        val directory = createTempDirectory("context-state-test")
        val file = directory.resolve("state.json")
        try {
            val ids = ArrayDeque(listOf("cp", "a", "b"))
            val first = ContextManager(JsonContextStateStore(file), FactExtractor { _, _ -> FactPatch(mapOf("lang" to "ru")) }, idFactory = { ids.removeFirst() })
            exchange(first, ContextStrategy.STICKY_FACTS, 2, "Пиши по-русски", "ok")
            exchange(first, ContextStrategy.BRANCHING, 10, "prefix", "ok")
            first.createCheckpoint("s")
            exchange(first, ContextStrategy.BRANCHING, 10, "a", "ok-a")

            val restored = ContextManager(JsonContextStateStore(file), FactExtractor { _, _ -> FactPatch() })
            assertEquals("ru", restored.state("s").stickyFacts["lang"])
            assertEquals(listOf("a", "ok-a"), restored.state("s").branching.activeBranch().messages.map { it.content })
            restored.clear("s")
            assertEquals(ContextSessionState("s"), ContextManager(JsonContextStateStore(file), FactExtractor { _, _ -> FactPatch() }).state("s"))
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun manager() = ContextManager(InMemoryContextStateStore(), FactExtractor { _, _ -> FactPatch() })
    private suspend fun exchange(manager: ContextManager, strategy: ContextStrategy, n: Int, user: String, answer: String) {
        val prepared = manager.prepare("s", user(user), ContextConfig(strategy, n))
        manager.commit(prepared, assistant(answer))
    }
    private fun user(value: String) = LlmMessage(LlmRole.USER, value)
    private fun assistant(value: String) = LlmMessage(LlmRole.ASSISTANT, value)
}
