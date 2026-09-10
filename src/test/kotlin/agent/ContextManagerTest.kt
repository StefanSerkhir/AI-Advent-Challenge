package org.example.agent

import kotlinx.coroutines.runBlocking
import org.example.llm.CompletionResult
import org.example.llm.LlmMessage
import org.example.llm.LlmRole
import org.example.llm.TokenUsage
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ContextManagerTest {
    @Test fun `history shorter than N stays unchanged`() = runBlocking {
        val fixture = fixture()
        repeat(9) { fixture.manager.addMessage("s", message(it)) }
        val state = fixture.manager.state("s")
        assertEquals((0..8).map { "m$it" }, state.recentMessages.map { it.message.content })
        assertTrue(state.pendingMessages.isEmpty())
        assertEquals(0, fixture.summarizer.calls)
    }

    @Test fun `history exactly N stays unchanged`() = runBlocking {
        val fixture = fixture()
        repeat(10) { fixture.manager.addMessage("s", message(it)) }
        assertEquals(10, fixture.manager.state("s").recentMessages.size)
        assertTrue(fixture.manager.state("s").pendingMessages.isEmpty())
    }

    @Test fun `messages exceeding the window enter pending queue`() = runBlocking {
        val fixture = fixture()
        repeat(11) { fixture.manager.addMessage("s", message(it)) }
        val state = fixture.manager.state("s")
        assertEquals(listOf("m0"), state.pendingMessages.map { it.message.content })
        assertEquals((1..10).map { "m$it" }, state.recentMessages.map { it.message.content })
    }

    @Test fun `full batch of ten is summarized and removed only from pending`() = runBlocking {
        val fixture = fixture()
        repeat(20) { fixture.manager.addMessage("s", message(it)) }
        val state = fixture.manager.state("s")
        assertEquals(1, fixture.summarizer.calls)
        assertTrue(state.pendingMessages.isEmpty())
        assertEquals((10..19).map { "m$it" }, state.recentMessages.map { it.message.content })
        (0..9).forEach { assertContains(state.summary, "m$it") }
    }

    @Test fun `multiple batches update cumulative summary`() = runBlocking {
        val fixture = fixture()
        repeat(30) { fixture.manager.addMessage("s", message(it)) }
        val state = fixture.manager.state("s")
        assertEquals(2, fixture.summarizer.calls)
        (0..19).forEach { assertContains(state.summary, "m$it") }
        assertEquals((20..29).map { "m$it" }, state.recentMessages.map { it.message.content })
    }

    @Test fun `remainder smaller than batch stays pending and remains in request context`() = runBlocking {
        val fixture = fixture()
        repeat(25) { fixture.manager.addMessage("s", message(it)) }
        val state = fixture.manager.state("s")
        assertEquals((10..14).map { "m$it" }, state.pendingMessages.map { it.message.content })
        val context = fixture.manager.contextFor("s", LlmMessage(LlmRole.USER, "now"))
        assertEquals((10..24).map { "m$it" } + "now", context.drop(2).map(LlmMessage::content))
    }

    @Test fun `role order and exact message content are preserved`() = runBlocking {
        val fixture = fixture(recent = 2, batch = 2)
        fixture.manager.addExchange("s", LlmMessage(LlmRole.USER, " exact user \n"), LlmMessage(LlmRole.ASSISTANT, "exact assistant"))
        val messages = fixture.manager.state("s").recentMessages.map(SequencedMessage::message)
        assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT), messages.map(LlmMessage::role))
        assertEquals(" exact user \n", messages.first().content)
    }

    @Test fun `summarized messages do not also occur in pending or recent`() = runBlocking {
        val fixture = fixture()
        repeat(22) { fixture.manager.addMessage("s", message(it)) }
        val state = fixture.manager.state("s")
        val active = (state.pendingMessages + state.recentMessages).map { it.message.content }.toSet()
        (0..9).forEach { assertFalse("m$it" in active) }
        (10..21).forEach { assertTrue("m$it" in active) }
    }

    @Test fun `request context has instructions summary active raw messages and current prompt in order`() = runBlocking {
        val fixture = fixture(recent = 2, batch = 2)
        repeat(5) { fixture.manager.addMessage("s", message(it)) }
        val context = fixture.manager.contextFor("s", LlmMessage(LlmRole.USER, "current"))
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER, LlmRole.USER),
            context.map(LlmMessage::role))
        assertEquals("system", context[0].content)
        assertContains(context[1].content, "m0")
        assertEquals(listOf("m2", "m3", "m4", "current"), context.drop(2).map(LlmMessage::content))
    }

    @Test fun `summarizer failure retains the complete pending batch`() = runBlocking {
        val store = InMemoryContextStateStore()
        val manager = ContextManager(ContextCompressionConfig(), store, HistorySummarizer { _, _ -> error("summary failed") })
        repeat(19) { manager.addMessage("s", message(it)) }
        assertFailsWith<IllegalStateException> { manager.addMessage("s", message(19)) }
        val state = manager.state("s")
        assertEquals((0..9).map { "m$it" }, state.pendingMessages.map { it.message.content })
        assertEquals((10..19).map { "m$it" }, state.recentMessages.map { it.message.content })
        assertEquals("", state.summary)
        assertEquals(1, state.stats.summarizationRequests)
    }

    @Test fun `disabled compression sends full history and never summarizes`() = runBlocking {
        val fixture = fixture(enabled = false)
        repeat(25) { fixture.manager.addMessage("s", message(it)) }
        val context = fixture.manager.contextFor("s", LlmMessage(LlmRole.USER, "now"))
        assertEquals(25, fixture.manager.state("s").recentMessages.size)
        assertEquals(27, context.size) // system + full history + current
        assertEquals(0, fixture.summarizer.calls)
    }

    @Test fun `usage metrics separate main calls and summary overhead`() = runBlocking {
        val fixture = fixture()
        repeat(20) { fixture.manager.addMessage("s", message(it)) }
        fixture.manager.recordMainUsage("s", TokenUsage(100, 30, 130))
        val stats = fixture.manager.state("s").stats
        assertEquals(1, stats.mainRequests)
        assertEquals(1, stats.summarizationRequests)
        assertEquals(100, stats.mainInputTokens)
        assertEquals(20, stats.summaryInputTokens)
        assertEquals(155, stats.totalTokens)
        assertEquals(100.0, stats.averageMainInputTokens)
    }

    @Test fun `session ids remain isolated`() = runBlocking {
        val fixture = fixture()
        fixture.manager.addMessage("alpha", LlmMessage(LlmRole.USER, "only alpha"))
        fixture.manager.addMessage("beta", LlmMessage(LlmRole.USER, "only beta"))
        assertEquals("only alpha", fixture.manager.state("alpha").recentMessages.single().message.content)
        assertEquals("only beta", fixture.manager.state("beta").recentMessages.single().message.content)
    }

    @Test fun `json store restores summary queues sequence timestamps and stats`() = runBlocking {
        val directory = createTempDirectory("context-state-test")
        val file = directory.resolve("state.json")
        try {
            val store = JsonContextStateStore(file)
            val fixture = ContextManager(ContextCompressionConfig(recentMessagesLimit = 2, summarizationBatchSize = 2),
                store, RecordingSummarizer(), clock = { 1234L })
            repeat(5) { fixture.addMessage("persisted", message(it)) }
            fixture.recordMainUsage("persisted", TokenUsage(7, 3, 10))

            val restored = JsonContextStateStore(file).load("persisted")!!
            assertContains(restored.summary, "m0")
            assertEquals(listOf("m2"), restored.pendingMessages.map { it.message.content })
            assertEquals(listOf("m3", "m4"), restored.recentMessages.map { it.message.content })
            assertEquals(listOf(3L, 4L, 5L), (restored.pendingMessages + restored.recentMessages).map { it.sequence })
            assertTrue((restored.pendingMessages + restored.recentMessages).all { it.timestampEpochMillis == 1234L })
            assertEquals(6L, restored.nextSequence)
            assertEquals(1, restored.stats.mainRequests)
            assertEquals(1, restored.stats.summarizationRequests)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun message(index: Int) = LlmMessage(
        if (index % 2 == 0) LlmRole.USER else LlmRole.ASSISTANT,
        "m$index",
    )

    private fun fixture(enabled: Boolean = true, recent: Int = 10, batch: Int = 10): Fixture {
        val summarizer = RecordingSummarizer()
        return Fixture(
            ContextManager(ContextCompressionConfig(enabled, recent, batch, "system"), InMemoryContextStateStore(), summarizer),
            summarizer,
        )
    }

    private data class Fixture(val manager: ContextManager, val summarizer: RecordingSummarizer)

    private class RecordingSummarizer : HistorySummarizer {
        var calls = 0
        override suspend fun summarize(previousSummary: String, messages: List<LlmMessage>): CompletionResult {
            calls++
            return CompletionResult(
                (listOf(previousSummary) + messages.map(LlmMessage::content)).filter(String::isNotBlank).joinToString("|"),
                "stop",
                TokenUsage(20, 5, 25),
                "fake-model",
            )
        }
    }
}
