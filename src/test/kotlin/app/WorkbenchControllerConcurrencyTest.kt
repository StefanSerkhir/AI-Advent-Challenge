package org.example.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.example.llm.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class WorkbenchControllerConcurrencyTest {
    @Test
    fun `partial response is published before completion and full response enters history`() = runBlocking {
        val firstDelta = CompletableDeferred<Unit>()
        val continueStream = CompletableDeferred<Unit>()
        val controller = WorkbenchController(
            AppSettings(LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            mapOf(LlmKind.OPENAI to "fake-key"),
            clientFactory = { _, _, _ -> object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
                    error("complete must not be used")

                override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                    emit(TextDelta("# Заг"))
                    firstDelta.complete(Unit)
                    continueStream.await()
                    delay(60)
                    emit(TextDelta("оловок"))
                    delay(60)
                    emit(CompletionFinished("stop", TokenUsage(3, 2, 5), "test-model"))
                }
            } },
        )
        try {
            assertTrue(controller.submit("Потоковый ответ"))
            withTimeout(2_000) { firstDelta.await() }
            val partial = withTimeout(2_000) {
                controller.state.first { it.exchanges.lastOrNull()?.outputs?.singleOrNull()?.completion?.content == "# Заг" }
            }
            assertTrue(partial.exchanges.single().outputs.single().streaming)
            assertEquals(0, partial.historyTurnCounts[ResponseVariant.UNRESTRICTED])

            continueStream.complete(Unit)
            withTimeout(2_000) { controller.awaitCurrentRequest() }

            val completed = controller.state.value
            val output = completed.exchanges.single().outputs.single()
            assertEquals("# Заголовок", output.completion?.content)
            assertFalse(output.streaming)
            assertIs<ExchangeOutcome.Completed>(completed.exchanges.single().outcome)
            assertEquals(1, completed.historyTurnCounts[ResponseVariant.UNRESTRICTED])
            assertEquals("# Заголовок", completed.historyMessages.getValue(ResponseVariant.UNRESTRICTED).last().content)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `cancellation keeps the partial card but does not save it in history`() = runBlocking {
        val firstDelta = CompletableDeferred<Unit>()
        val controller = WorkbenchController(
            AppSettings(LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            mapOf(LlmKind.OPENAI to "fake-key"),
            clientFactory = { _, _, _ -> object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
                    error("complete must not be used")

                override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                    emit(TextDelta("Частичный "))
                    emit(TextDelta("ответ полностью"))
                    firstDelta.complete(Unit)
                    awaitCancellation()
                }
            } },
        )
        try {
            assertTrue(controller.submit("Отмени меня"))
            withTimeout(2_000) { firstDelta.await() }
            controller.cancelCurrent()
            withTimeout(2_000) { controller.awaitCurrentRequest() }

            val state = controller.state.value
            val output = state.exchanges.single().outputs.single()
            assertEquals("Частичный ответ полностью", output.completion?.content)
            assertFalse(output.streaming)
            assertIs<ExchangeOutcome.Cancelled>(state.exchanges.single().outcome)
            assertEquals(0, state.historyTurnCounts[ResponseVariant.UNRESTRICTED])
            assertTrue(state.historyMessages[ResponseVariant.UNRESTRICTED].orEmpty().isEmpty())
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `cancellation before worker dispatch returns to idle and permits the next request`() = runBlocking {
        val queue = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        var calls = 0
        val controller = WorkbenchController(
            AppSettings(LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            mapOf(LlmKind.OPENAI to "fake-key"),
            clientFactory = { _, _, _ -> object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    calls++
                    return CompletionResult("Ответ", "stop", null)
                }
            } },
            workerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        fun drain() { while (true) (queue.poll() ?: break).run() }
        try {
            assertTrue(controller.submit("Отменить до запуска"))
            controller.cancelCurrent()
            drain()
            assertFalse(controller.state.value.isRunning)
            assertIs<ExchangeOutcome.Cancelled>(controller.state.value.exchanges.single().outcome)
            assertEquals(0, calls)
            assertTrue(controller.submit("Новый запрос"))
            drain()
            assertFalse(controller.state.value.isRunning)
            assertIs<ExchangeOutcome.Completed>(controller.state.value.exchanges.last().outcome)
            assertEquals(1, calls)
        } finally { controller.close(); drain(); controller.shutdown() }
    }
}
