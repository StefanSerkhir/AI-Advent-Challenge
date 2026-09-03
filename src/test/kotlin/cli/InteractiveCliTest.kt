package org.example.cli

import kotlinx.coroutines.runBlocking
import org.example.app.AppSettings
import org.example.app.ResponseMode
import org.example.llm.*
import kotlin.test.*

class InteractiveCliTest {

    @Test
    fun `commands change settings at runtime without exposing key`() = runBlocking {
        val terminal = RecordingTerminal()
        val settings = AppSettings(llmKind = LlmKind.DEEPSEEK)
        val cli = InteractiveCli(
            settings = settings,
            initialApiKeys = emptyMap(),
            clientFactory = { _, _ -> StubLlmClient },
            terminal = terminal,
        )

        cli.processLine("/provider OpenAI")
        cli.processLine("/api-key secret-value")
        cli.processLine("/mode controlled")
        cli.processLine("/max-tokens 450")
        cli.processLine("/max-words 40")
        cli.processLine("/format bullets 4")
        cli.processLine("/stop off")
        cli.processLine("/history off")
        cli.processLine("/settings")

        assertEquals(LlmKind.OPENAI, settings.llmKind)
        assertEquals(ResponseMode.CONTROLLED, settings.responseMode)
        assertEquals(450, settings.maxTokens)
        assertEquals(40, settings.maxWords)
        assertEquals(4, settings.bulletCount)
        assertEquals(null, settings.stopSequence)
        assertFalse(settings.historyEnabled)
        assertContains(terminal.output.toString(), "API-ключ: задан")
        assertFalse("secret-value" in terminal.output.toString())
    }

    @Test
    fun `request failure does not stop interactive session`() = runBlocking {
        val terminal = RecordingTerminal()
        val cli = InteractiveCli(
            settings = AppSettings(llmKind = LlmKind.OPENAI),
            initialApiKeys = mapOf(LlmKind.OPENAI to "test-key"),
            clientFactory = { _, _ -> FailingLlmClient },
            terminal = terminal,
        )

        val keepRunning = cli.processLine("Тестовый запрос")

        assertTrue(keepRunning)
        assertContains(terminal.output.toString(), "Ошибка выполнения запроса: test failure")
    }

    @Test
    fun `terminal title reflects provider and resets on exit`() = runBlocking {
        val terminal = RecordingTerminal(ArrayDeque(listOf("/provider OpenAI", "/exit")))
        val cli = InteractiveCli(
            settings = AppSettings(llmKind = LlmKind.DEEPSEEK),
            initialApiKeys = emptyMap(),
            clientFactory = { _, _ -> StubLlmClient },
            terminal = terminal,
        )

        cli.run()

        assertEquals(
            listOf("LLM CLI — Deepseek", "LLM CLI — OpenAI", ""),
            terminal.titles,
        )
    }

    @Test
    fun `successful request prints comparison table`() = runBlocking {
        val terminal = RecordingTerminal()
        val cli = InteractiveCli(
            settings = AppSettings(
                llmKind = LlmKind.OPENAI,
                responseMode = ResponseMode.CONTROLLED,
            ),
            initialApiKeys = mapOf(LlmKind.OPENAI to "test-key"),
            clientFactory = { _, _ -> StubLlmClient },
            terminal = terminal,
        )

        cli.processLine("Тестовый запрос")

        assertContains(terminal.output.toString(), "Результат сравнения")
        assertContains(
            terminal.output.toString(),
            "│ с ограничениями │       13 │    2 │       7 │ stop          │",
        )
    }

    @Test
    fun `reasoning mode prints four solutions generated prompt and evaluation`() = runBlocking {
        val terminal = RecordingTerminal()
        val settings = AppSettings(
            llmKind = LlmKind.OPENAI,
            responseMode = ResponseMode.REASONING,
        )
        var call = 0
        val cli = InteractiveCli(
            settings = settings,
            initialApiKeys = mapOf(LlmKind.OPENAI to "test-key"),
            clientFactory = { _, _ ->
                object : LlmClient {
                    override suspend fun complete(
                        messages: List<LlmMessage>,
                        options: CompletionOptions,
                    ): CompletionResult {
                        call++
                        val content = if (call == 3) "готовый тестовый промпт" else "ответ-$call"
                        return CompletionResult(content, "stop", null)
                    }
                }
            },
            terminal = terminal,
        )

        cli.processLine("Тестовая логическая задача")

        val output = terminal.output.toString()
        assertEquals(6, call)
        assertContains(output, "1. ПРЯМОЙ ОТВЕТ")
        assertContains(output, "[1/6] Прямой ответ…")
        assertContains(output, "[6/6] Сравнение и оценка точности…")
        assertContains(output, "2. РЕШЕНИЕ ПОШАГОВО")
        assertContains(output, "готовый тестовый промпт")
        assertContains(output, "4. ГРУППА ЭКСПЕРТОВ")
        assertContains(output, "СРАВНЕНИЕ И ОЦЕНКА ТОЧНОСТИ")
    }

    @Test
    fun `temperature mode runs three values and prints evaluation`() = runBlocking {
        val terminal = RecordingTerminal()
        val settings = AppSettings(
            llmKind = LlmKind.DEEPSEEK,
            responseMode = ResponseMode.TEMPERATURE,
        )
        val temperatures = mutableListOf<Double?>()
        val cli = InteractiveCli(
            settings = settings,
            initialApiKeys = mapOf(LlmKind.DEEPSEEK to "test-key"),
            clientFactory = { _, _ ->
                object : LlmClient {
                    override suspend fun complete(
                        messages: List<LlmMessage>,
                        options: CompletionOptions,
                    ): CompletionResult {
                        temperatures += options.temperature
                        return CompletionResult("ответ-${temperatures.size}", "stop", null)
                    }
                }
            },
            terminal = terminal,
        )

        cli.processLine("Придумай слоган")

        assertEquals(listOf<Double?>(0.0, 0.7, 1.2, 0.0), temperatures)
        val output = terminal.output.toString()
        assertContains(output, "TEMPERATURE = 0")
        assertContains(output, "TEMPERATURE = 0.7")
        assertContains(output, "TEMPERATURE = 1.2")
        assertContains(output, "ВЫВОДЫ ПО ИСПОЛЬЗОВАНИЮ")
    }

    private class RecordingTerminal(
        private val input: ArrayDeque<String> = ArrayDeque(),
    ) : Terminal {
        val output = StringBuilder()
        val titles = mutableListOf<String>()

        override fun readLine(): String? = input.removeFirstOrNull()

        override fun print(text: String) {
            output.append(text)
        }

        override fun println(text: String) {
            output.appendLine(text)
        }

        override fun setTitle(title: String) {
            titles += title
        }
    }

    private object StubLlmClient : LlmClient {
        override suspend fun complete(
            messages: List<LlmMessage>,
            options: CompletionOptions,
        ): CompletionResult = CompletionResult(
            content = "test response",
            finishReason = "stop",
            usage = TokenUsage(promptTokens = 5, completionTokens = 7, totalTokens = 12),
        )
    }

    private object FailingLlmClient : LlmClient {
        override suspend fun complete(
            messages: List<LlmMessage>,
            options: CompletionOptions,
        ): CompletionResult = error("test failure")
    }
}
