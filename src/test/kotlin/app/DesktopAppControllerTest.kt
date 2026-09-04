package org.example.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.llm.*
import kotlin.test.*

class DesktopAppControllerTest {

    @Test
    fun `request exposes progress prevents duplicate send and completes without blocking caller`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        var selectedModel: String? = null
        val client = object : LlmClient {
            override suspend fun complete(
                messages: List<LlmMessage>,
                options: CompletionOptions,
            ): CompletionResult {
                release.await()
                return CompletionResult("готовый ответ", "stop", null)
            }
        }
        val controller = DesktopAppController(
            initialSettings = AppSettings(
                llmKind = LlmKind.OPENAI,
                model = "gpt-4.1-mini",
                responseMode = ResponseMode.UNRESTRICTED,
            ),
            initialApiKeys = mapOf(LlmKind.OPENAI to "dummy-key"),
            clientFactory = { _, _, model -> selectedModel = model; client },
        )

        try {
            assertTrue(controller.submit("Тестовый запрос"))
            assertFalse(controller.submit("Повторный запрос"))
            val running = withTimeout(2_000) {
                controller.state.first {
                    (it.operation as? OperationState.Running)?.progress?.current == 1
                }
            }
            assertEquals("Ответ без ограничений", (running.operation as OperationState.Running).progress.label)

            release.complete(Unit)
            controller.awaitCurrentRequest()

            assertEquals("gpt-4.1-mini", selectedModel)
            assertIs<OperationState.Idle>(controller.state.value.operation)
            val outcome = controller.state.value.exchanges.single().outcome
            val result = assertIs<ExchangeOutcome.Completed>(outcome).result
            assertEquals("готовый ответ", assertIs<RequestResult.Responses>(result).responses.single().content)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `cancel marks request and returns controller to idle`(): Unit = runBlocking {
        val controller = DesktopAppController(
            initialSettings = AppSettings(LlmKind.DEEPSEEK, responseMode = ResponseMode.CONTROLLED),
            initialApiKeys = mapOf(LlmKind.DEEPSEEK to "dummy-key"),
            clientFactory = { _, _, _ ->
                object : LlmClient {
                    override suspend fun complete(
                        messages: List<LlmMessage>,
                        options: CompletionOptions,
                    ): CompletionResult = awaitCancellation()
                }
            },
        )

        try {
            assertTrue(controller.submit("Долгий запрос"))
            withTimeout(2_000) { controller.state.first { it.isRunning } }
            controller.cancelCurrent()
            controller.awaitCurrentRequest()

            assertIs<ExchangeOutcome.Cancelled>(controller.state.value.exchanges.single().outcome)
            assertIs<OperationState.Idle>(controller.state.value.operation)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `failure is shown without exposing configured API key`(): Unit = runBlocking {
        val dummyKey = "dummy-secret-value"
        val controller = DesktopAppController(
            initialSettings = AppSettings(LlmKind.OPENAI, responseMode = ResponseMode.UNRESTRICTED),
            initialApiKeys = mapOf(LlmKind.OPENAI to dummyKey),
            clientFactory = { _, _, _ ->
                object : LlmClient {
                    override suspend fun complete(
                        messages: List<LlmMessage>,
                        options: CompletionOptions,
                    ): CompletionResult = error("network failed near $dummyKey")
                }
            },
        )

        try {
            assertTrue(controller.submit("Запрос с ошибкой"))
            controller.awaitCurrentRequest()

            val failure = assertIs<ExchangeOutcome.Failed>(controller.state.value.exchanges.single().outcome)
            assertFalse(failure.message.contains(dummyKey))
            assertTrue(failure.message.contains("••••"))
            assertIs<OperationState.Idle>(controller.state.value.operation)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `missing key becomes recoverable UI error`() {
        val controller = DesktopAppController(
            initialSettings = AppSettings(LlmKind.DEEPSEEK),
            initialApiKeys = emptyMap(),
            clientFactory = { _, _, _ -> error("must not be called") },
        )

        try {
            assertFalse(controller.submit("Запрос"))
            assertEquals(NoticeKind.ERROR, controller.state.value.notice?.kind)
            assertTrue(controller.state.value.exchanges.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `model comparison uses OpenAI key and all target models regardless of selected model`(): Unit = runBlocking {
        val factoryCalls = mutableListOf<Pair<LlmKind, String>>()
        val controller = DesktopAppController(
            initialSettings = AppSettings(
                llmKind = LlmKind.DEEPSEEK,
                model = "deepseek-v4-flash",
                responseMode = ResponseMode.MODEL_COMPARISON,
                maxTokens = 800,
            ),
            initialApiKeys = mapOf(LlmKind.OPENAI to "openai-dummy-key"),
            clientFactory = { kind, _, model ->
                factoryCalls += kind to model
                object : LlmClient {
                    override suspend fun complete(
                        messages: List<LlmMessage>,
                        options: CompletionOptions,
                    ): CompletionResult = CompletionResult(
                        content = "Ответ $model",
                        finishReason = "stop",
                        usage = TokenUsage(12, 8, 20),
                        model = model,
                    )
                }
            },
        )

        try {
            assertTrue(controller.submit("Сравни этот запрос"))
            controller.awaitCurrentRequest()

            assertEquals(
                listOf(
                    LlmKind.OPENAI to "gpt-5.6-luna",
                    LlmKind.OPENAI to "gpt-5.6-terra",
                    LlmKind.OPENAI to "gpt-5.6-sol",
                    LlmKind.OPENAI to "gpt-5.6-sol",
                ),
                factoryCalls,
            )
            val completed = assertIs<ExchangeOutcome.Completed>(
                controller.state.value.exchanges.single().outcome,
            )
            val report = assertIs<RequestResult.ModelComparison>(completed.result).report
            assertEquals(3, report.runs.size)
            assertNotNull(report.evaluation)
        } finally {
            controller.close()
        }
    }
}
