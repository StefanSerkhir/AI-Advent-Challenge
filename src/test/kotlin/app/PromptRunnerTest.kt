package org.example.app

import kotlinx.coroutines.runBlocking
import org.example.agent.*
import org.example.llm.*
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.*

class PromptRunnerTest {

    @Test
    fun `compare mode keeps independent history for both variants`() = runBlocking {
        val client = RecordingLlmClient()
        val runner = PromptRunner { client }
        val settings = AppSettings(llmKind = LlmKind.OPENAI)

        runner.complete("Первый вопрос", settings)
        runner.complete("Второй вопрос", settings)

        assertEquals(4, client.calls.size)
        assertEquals("Первый вопрос", client.calls[0].messages.single().content)
        assertContains(client.calls[1].messages.single().content, "Требования к ответу")
        assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER), client.calls[2].messages.map { it.role })
        assertEquals(listOf(LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER), client.calls[3].messages.map { it.role })
        assertEquals("answer-1", client.calls[2].messages[1].content)
        assertEquals("answer-2", client.calls[3].messages[1].content)
        assertNull(client.calls[0].options.maxTokens)
        assertEquals(300, client.calls[1].options.maxTokens)
    }

    @Test
    fun `completed token metrics restore after restart and legacy usage stays unavailable`() = runBlocking {
        val directory = createTempDirectory("llm-metrics-restart-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        try {
            val settings = AppSettings(LlmKind.OPENAI, model = "gpt-5.6-sol", responseMode = ResponseMode.UNRESTRICTED)
            val first = PromptRunner(historyStore = JsonConversationHistoryStore(file), clientProvider = { RecordingLlmClient() })
            first.complete("first", settings)
            val restored = PromptRunner(historyStore = JsonConversationHistoryStore(file), clientProvider = { RecordingLlmClient() })
            assertEquals(1, restored.tokenMetricsSnapshot().getValue(ResponseVariant.UNRESTRICTED).size)
            assertEquals(10, restored.tokenTotalsSnapshot().getValue(ResponseVariant.UNRESTRICTED).cumulativeApiInputTokens)

            file.writeText("""{"version":1,"branches":[{"id":"unrestricted","messages":[{"role":"user","text":"old"},{"role":"assistant","text":"answer"}]}]}""")
            val legacy = PromptRunner(historyStore = JsonConversationHistoryStore(file), clientProvider = { RecordingLlmClient() })
            assertTrue(legacy.tokenMetricsSnapshot().getValue(ResponseVariant.UNRESTRICTED).isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `history can be disabled and cleared`() = runBlocking {
        val client = RecordingLlmClient()
        val runner = PromptRunner { client }
        val settings = AppSettings(llmKind = LlmKind.OPENAI)

        runner.complete("Сохрани", settings)
        settings.historyEnabled = false
        runner.complete("Без истории", settings)

        assertEquals(1, client.calls[2].messages.size)
        assertEquals(1, client.calls[3].messages.size)

        runner.clearHistory()
        settings.historyEnabled = true
        runner.complete("После очистки", settings)

        assertEquals(1, client.calls[4].messages.size)
        assertEquals(1, client.calls[5].messages.size)
    }

    @Test
    fun `a new runtime restores persisted context before its first LLM call`() = runBlocking {
        val directory = createTempDirectory("llm-restart-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        val contextFile = directory.resolve(DEFAULT_CONTEXT_STATE_FILE_NAME)
        try {
            val firstClient = RecordingLlmClient()
            PromptRunner(
                historyStore = JsonConversationHistoryStore(file),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { firstClient },
            ).complete(
                "Первый вопрос",
                AppSettings(llmKind = LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            )

            val secondClient = RecordingLlmClient()
            val secondRuntime = PromptRunner(
                historyStore = JsonConversationHistoryStore(file),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { secondClient },
            )
            secondRuntime.complete(
                "Второй вопрос",
                AppSettings(llmKind = LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            )

            assertEquals(
                listOf(LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER),
                secondClient.calls.single().messages.map(LlmMessage::role),
            )
            assertEquals("Первый вопрос", secondClient.calls.single().messages[1].content)
            assertEquals("answer-1", secondClient.calls.single().messages[2].content)
            assertEquals("Второй вопрос", secondClient.calls.single().messages[3].content)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sliding context is restored and used by the UI agent after restart`() = runBlocking {
        val directory = createTempDirectory("managed-context-restart-test")
        val historyFile = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        val contextFile = directory.resolve(DEFAULT_CONTEXT_STATE_FILE_NAME)
        val settings = AppSettings(
            llmKind = LlmKind.OPENAI,
            responseMode = ResponseMode.UNRESTRICTED,
            recentMessagesLimit = 2,
            contextStrategy = ContextStrategy.SLIDING_WINDOW,
        )
        try {
            val firstClient = RecordingLlmClient()
            val first = PromptRunner(
                historyStore = JsonConversationHistoryStore(historyFile),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { firstClient },
            )
            first.complete("first", settings)
            first.complete("second", settings)
            assertEquals(2, firstClient.calls.size)

            val restartedClient = RecordingLlmClient()
            val restarted = PromptRunner(
                historyStore = JsonConversationHistoryStore(historyFile),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { restartedClient },
            )
            restarted.complete("third", settings)

            val sent = restartedClient.calls.single().messages
            assertEquals(
                listOf(LlmRole.SYSTEM, LlmRole.ASSISTANT, LlmRole.USER),
                sent.map(LlmMessage::role),
            )
            assertFalse(sent.any { it.content == "first" })
            assertEquals(listOf("answer-2", "third"), sent.takeLast(2).map(LlmMessage::content))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persisted response branches remain independent`() = runBlocking {
        val directory = createTempDirectory("llm-history-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        val contextFile = directory.resolve(DEFAULT_CONTEXT_STATE_FILE_NAME)
        try {
            val client = RecordingLlmClient()
            PromptRunner(
                historyStore = JsonConversationHistoryStore(file),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { client },
            ).complete("Вопрос", AppSettings(llmKind = LlmKind.OPENAI))

            val restored = JsonConversationHistoryStore(file).load()
            assertEquals("answer-1", restored.getValue("unrestricted")[1].content)
            assertEquals("answer-2", restored.getValue("controlled")[1].content)
            assertEquals("Вопрос", restored.getValue("unrestricted")[0].content)
            assertContains(restored.getValue("controlled")[0].content, "Требования к ответу")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `disabled history neither reads nor changes persisted exchanges`() = runBlocking {
        val directory = createTempDirectory("llm-history-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        val contextFile = directory.resolve(DEFAULT_CONTEXT_STATE_FILE_NAME)
        val settings = AppSettings(llmKind = LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED)
        try {
            val client = RecordingLlmClient()
            val runner = PromptRunner(
                historyStore = JsonConversationHistoryStore(file),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { client },
            )
            runner.complete("Сохрани", settings)
            val persistedBefore = file.toFile().readText()

            settings.historyEnabled = false
            runner.complete("Не сохраняй", settings)
            assertEquals(listOf(LlmRole.USER), client.calls[1].messages.map(LlmMessage::role))
            assertEquals(persistedBefore, file.toFile().readText())

            settings.historyEnabled = true
            val restartedClient = RecordingLlmClient()
            PromptRunner(
                historyStore = JsonConversationHistoryStore(file),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { restartedClient },
            ).complete("Продолжи", settings)
            assertEquals(
                listOf(LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER),
                restartedClient.calls.single().messages.map(LlmMessage::role),
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clear is persisted and a restarted runtime stays empty`() = runBlocking {
        val directory = createTempDirectory("llm-history-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        val contextFile = directory.resolve(DEFAULT_CONTEXT_STATE_FILE_NAME)
        val settings = AppSettings(llmKind = LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED)
        try {
            val runner = PromptRunner(
                historyStore = JsonConversationHistoryStore(file),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { RecordingLlmClient() },
            )
            runner.complete("Будет удалено", settings)
            runner.clearHistory()

            assertTrue(JsonConversationHistoryStore(file).load().isEmpty())
            val restartedClient = RecordingLlmClient()
            PromptRunner(
                historyStore = JsonConversationHistoryStore(file),
                contextStateStore = JsonContextStateStore(contextFile),
                clientProvider = { restartedClient },
            ).complete("После очистки", settings)
            assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER), restartedClient.calls.single().messages.map(LlmMessage::role))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid persisted history produces a safe warning and starts empty`() = runBlocking {
        val directory = createTempDirectory("llm-history-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        try {
            file.writeText("secret-looking broken payload")
            val client = RecordingLlmClient()
            val runner = PromptRunner(
                historyStore = JsonConversationHistoryStore(file),
                clientProvider = { client },
            )

            assertNotNull(runner.historyLoadWarning)
            assertTrue("secret-looking" !in runner.historyLoadWarning.orEmpty())
            runner.complete(
                "Новый диалог",
                AppSettings(llmKind = LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            )
            assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER), client.calls.single().messages.map(LlmMessage::role))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `write failure leaves memory and persisted snapshot unchanged`() = runBlocking {
        val store = FailingHistoryStore()
        val client = RecordingLlmClient()
        var publishedResponses = 0
        val runner = PromptRunner(
            onResponse = { publishedResponses++ },
            historyStore = store,
            clientProvider = { client },
        )

        assertFailsWith<HistoryPersistenceException> {
            runner.complete(
                "Успешный ответ при сбое диска",
                AppSettings(llmKind = LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            )
        }

        assertTrue(runner.historySnapshot().getValue(ResponseVariant.UNRESTRICTED).isEmpty())
        assertTrue(store.persisted.isEmpty())
        assertEquals(0, publishedResponses)
    }

    private class RecordingLlmClient : LlmClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(
            messages: List<LlmMessage>,
            options: CompletionOptions,
        ): CompletionResult {
            calls += Call(messages, options)
            return CompletionResult(
                content = "answer-${calls.size}",
                finishReason = "stop",
                usage = TokenUsage(10, calls.size, 10 + calls.size),
            )
        }
    }

    private data class Call(
        val messages: List<LlmMessage>,
        val options: CompletionOptions,
    )

    private class FailingHistoryStore : ConversationHistoryStore {
        var persisted: ConversationHistorySnapshot = emptyMap()

        override fun load(): ConversationHistorySnapshot = persisted

        override fun save(history: ConversationHistorySnapshot) {
            throw HistoryPersistenceException(IOException("fixture disk failure"))
        }
    }
}
