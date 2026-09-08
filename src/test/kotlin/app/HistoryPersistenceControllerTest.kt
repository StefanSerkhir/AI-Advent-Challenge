package org.example.app

import kotlinx.coroutines.runBlocking
import org.example.agent.ConversationHistorySnapshot
import org.example.agent.ConversationHistoryStore
import org.example.agent.HistoryPersistenceException
import org.example.llm.*
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HistoryPersistenceControllerTest {
    @Test
    fun `history write failure publishes failure without publishing messages`() = runBlocking {
        val store = FailingStore()
        val controller = WorkbenchController(
            initialSettings = AppSettings(LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            initialApiKeys = mapOf(LlmKind.OPENAI to "dummy-key"),
            historyStore = store,
            clientFactory = { _, _, _ -> SuccessfulClient },
        )

        try {
            assertTrue(controller.submit("Запрос"))
            controller.awaitCurrentRequest()

            assertIs<ExchangeOutcome.Failed>(controller.state.value.exchanges.single().outcome)
            assertEquals(NoticeKind.ERROR, controller.state.value.notice?.kind)
            assertTrue(controller.state.value.notice?.message.orEmpty().contains("историю"))
            assertTrue(controller.state.value.historyMessages.values.flatten().isEmpty())
            assertEquals(mapOf(ResponseVariant.UNRESTRICTED to 0, ResponseVariant.CONTROLLED to 0), controller.state.value.historyTurnCounts)
            assertTrue(store.persisted.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `failed persistent clear keeps memory and reports no success`() = runBlocking {
        val store = FailingStore(fail = false)
        val controller = WorkbenchController(
            initialSettings = AppSettings(LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            initialApiKeys = mapOf(LlmKind.OPENAI to "dummy-key"),
            historyStore = store,
            clientFactory = { _, _, _ -> SuccessfulClient },
        )

        try {
            controller.submit("Сохрани")
            controller.awaitCurrentRequest()
            store.fail = true

            controller.clearHistory()

            assertEquals(NoticeKind.ERROR, controller.state.value.notice?.kind)
            assertTrue(controller.state.value.notice?.message.orEmpty().contains("Не удалось"))
            assertEquals(1, controller.state.value.historyTurnCounts.getValue(ResponseVariant.UNRESTRICTED))
            assertEquals(2, controller.state.value.historyMessages.getValue(ResponseVariant.UNRESTRICTED).size)
            assertEquals(2, store.persisted.getValue("unrestricted").size)
        } finally {
            controller.close()
        }
    }

    private class FailingStore(var fail: Boolean = true) : ConversationHistoryStore {
        var persisted: ConversationHistorySnapshot = emptyMap()

        override fun load(): ConversationHistorySnapshot = persisted

        override fun save(history: ConversationHistorySnapshot) {
            if (fail) throw HistoryPersistenceException(IOException("fixture disk failure"))
            persisted = history.mapValues { it.value.toList() }
        }
    }

    private object SuccessfulClient : LlmClient {
        override suspend fun complete(
            messages: List<LlmMessage>,
            options: CompletionOptions,
        ): CompletionResult = CompletionResult("Готовый ответ", "stop", null)
    }
}
