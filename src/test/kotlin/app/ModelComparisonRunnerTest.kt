package org.example.app

import kotlinx.coroutines.runBlocking
import org.example.llm.*
import kotlin.test.*

class ModelComparisonRunnerTest {

    @Test
    fun `same prompt and token limit are sent to all targets before blind evaluation`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<String, String, Int?>>()
        var clock = 0L
        val runner = ModelComparisonRunner(
            clientProvider = { model ->
                object : LlmClient {
                    override suspend fun complete(
                        messages: List<LlmMessage>,
                        options: CompletionOptions,
                    ): CompletionResult {
                        val prompt = messages.single().content
                        calls += Triple(model, prompt, options.maxTokens)
                        assertEquals(ReasoningEffort.MEDIUM, options.reasoningEffort)
                        return CompletionResult(
                            content = if (prompt.contains("<answer label=")) "Ответ A лучший" else "Ответ $model",
                            finishReason = "stop",
                            usage = TokenUsage(10, 5, 15, reasoningTokens = 2),
                            model = model,
                        )
                    }
                }
            },
            nanoTime = {
                clock += 1_000_000
                clock
            },
        )

        val report = runner.compare("Одинаковый запрос", maxTokens = 900)

        assertEquals(
            listOf("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.6-sol"),
            calls.map { it.first },
        )
        assertEquals(List(3) { "Одинаковый запрос" }, calls.take(3).map { it.second })
        assertTrue(calls.all { it.third == 900 })
        assertEquals(List(4) { 1L }, report.runs.map { it.elapsedMillis } + report.evaluation!!.elapsedMillis)

        val evaluationPrompt = calls.last().second
        assertTrue(evaluationPrompt.contains("<answer label=\"A\">"))
        assertTrue(evaluationPrompt.contains("<answer label=\"B\">"))
        assertTrue(evaluationPrompt.contains("<answer label=\"C\">"))
        assertFalse(evaluationPrompt.contains("gpt-5.6-luna"))
        assertFalse(evaluationPrompt.contains("gpt-5.6-terra"))
        assertFalse(evaluationPrompt.contains("gpt-5.6-sol"))
    }

    @Test
    fun `cost includes regular cached cache-write and completion tokens`() {
        val target = MODEL_COMPARISON_TARGETS.first()
        val usage = TokenUsage(
            promptTokens = 100,
            completionTokens = 200,
            totalTokens = 300,
            cachedPromptTokens = 10,
            cacheWritePromptTokens = 20,
            reasoningTokens = 30,
        )

        // 70×$0.20 + 10×$0.02 + 20×($0.20×1.25) + 200×$1.20, per million.
        assertEquals(0.0002592, target.estimatedCostUsd(usage), absoluteTolerance = 0.0000000001)
    }

    @Test
    fun `one failed model does not discard successful runs or evaluation`(): Unit = runBlocking {
        val runner = ModelComparisonRunner(
            clientProvider = { model ->
                object : LlmClient {
                    override suspend fun complete(
                        messages: List<LlmMessage>,
                        options: CompletionOptions,
                    ): CompletionResult {
                        if (model == "gpt-5.6-terra") error("model unavailable")
                        return CompletionResult("Готово", "stop", null, model)
                    }
                }
            },
            errorMessage = { "Безопасная ошибка: ${it.message}" },
        )

        val report = runner.compare("Запрос", 500)

        assertEquals(3, report.runs.size)
        val failure = assertIs<ModelCallOutcome.Failure>(report.runs[1].outcome)
        assertEquals("Безопасная ошибка: model unavailable", failure.message)
        assertNotNull(report.runs[0].completion)
        assertNotNull(report.runs[2].completion)
        assertIs<ModelCallOutcome.Success>(assertNotNull(report.evaluation).outcome)
    }
}
