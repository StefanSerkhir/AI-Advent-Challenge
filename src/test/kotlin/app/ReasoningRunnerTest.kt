package org.example.app

import kotlinx.coroutines.runBlocking
import org.example.llm.CompletionOptions
import org.example.llm.CompletionResult
import org.example.llm.LlmClient
import org.example.llm.LlmMessage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ReasoningRunnerTest {

    @Test
    fun `runs four reasoning strategies and evaluates their answers`() = runBlocking {
        val progressEvents = mutableListOf<ReasoningProgress>()
        val client = ScriptedLlmClient(
            responses = ArrayDeque(
                listOf(
                    "прямое решение",
                    "пошаговое решение",
                    "Сгенерированный промпт",
                    "решение по промпту",
                    "решения экспертов",
                    "сравнение",
                ),
            ),
        )
        val runner = ReasoningRunner(onProgress = { progressEvents += it }) { client }

        val report = runner.compare("Тестовая задача", "Эталон")

        assertEquals(6, client.prompts.size)
        assertEquals("Тестовая задача", client.prompts[0])
        assertContains(client.prompts[1], "Решай пошагово")
        assertContains(client.prompts[2], "Не решай задачу сам")
        assertEquals("Сгенерированный промпт", client.prompts[3])
        assertContains(client.prompts[4], "Аналитик")
        assertContains(client.prompts[4], "Инженер")
        assertContains(client.prompts[4], "Критик")
        assertContains(client.prompts[5], "Эталон")
        assertContains(client.prompts[5], "прямое решение")
        assertContains(client.prompts[5], "решения экспертов")
        assertEquals("Сгенерированный промпт", report.generatedPrompt)
        assertEquals(
            listOf(
                ReasoningVariant.DIRECT,
                ReasoningVariant.STEP_BY_STEP,
                ReasoningVariant.GENERATED_PROMPT,
                ReasoningVariant.EXPERT_PANEL,
            ),
            report.solutions.map(ReasoningSolution::variant),
        )
        assertEquals("сравнение", report.evaluation.content)
        assertEquals((1..6).toList(), progressEvents.map(ReasoningProgress::current))
        assertEquals(List(6) { TOTAL_REASONING_API_CALLS }, progressEvents.map(ReasoningProgress::total))
        assertEquals("Сравнение и оценка точности", progressEvents.last().label)
    }

    @Test
    fun `evaluator creates its own reference when none is supplied`() = runBlocking {
        val client = ScriptedLlmClient(
            ArrayDeque(listOf("1", "2", "готовый промпт", "3", "4", "оценка")),
        )

        ReasoningRunner { client }.compare("Задача без эталона")

        assertContains(client.prompts.last(), "Эталон не задан")
        assertContains(client.prompts.last(), "реши задачу самостоятельно")
    }

    private class ScriptedLlmClient(
        private val responses: ArrayDeque<String>,
    ) : LlmClient {
        val prompts = mutableListOf<String>()

        override suspend fun complete(
            messages: List<LlmMessage>,
            options: CompletionOptions,
        ): CompletionResult {
            prompts += messages.single().content
            return CompletionResult(
                content = responses.removeFirst(),
                finishReason = "stop",
                usage = null,
            )
        }
    }
}
