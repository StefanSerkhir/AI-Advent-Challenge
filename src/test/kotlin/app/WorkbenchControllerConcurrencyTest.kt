package org.example.app

import kotlinx.coroutines.*
import org.example.llm.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class WorkbenchControllerConcurrencyTest {
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
