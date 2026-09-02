package org.example.app

import org.example.llm.CompletionResult
import org.example.llm.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseComparisonTest {

    @Test
    fun `metrics count Unicode characters words and completion tokens`() {
        val response = LabeledResponse(
            variant = ResponseVariant.UNRESTRICTED,
            completion = CompletionResult(
                content = "Привет 👋 мир",
                finishReason = "length",
                usage = TokenUsage(10, 4, 14),
            ),
        )

        assertEquals(
            ResponseMetrics(
                run = "без ограничений",
                characterCount = 12,
                wordCount = 3,
                completionTokens = 4,
                finishReason = "length",
            ),
            response.metrics(),
        )
    }

    @Test
    fun `table uses fallback when API omits metadata`() {
        val table = renderComparisonTable(
            listOf(
                LabeledResponse(
                    ResponseVariant.CONTROLLED,
                    CompletionResult("Короткий ответ", null, null),
                ),
            ),
        )

        assertEquals(
            """
            ┌─────────────────┬──────────┬──────┬─────────┬───────────────┐
            │ прогон          │ символов │ слов │ токенов │ finish_reason │
            ├─────────────────┼──────────┼──────┼─────────┼───────────────┤
            │ с ограничениями │       14 │    2 │     н/д │ н/д           │
            └─────────────────┴──────────┴──────┴─────────┴───────────────┘
            """.trimIndent(),
            table,
        )
    }

    @Test
    fun `reasoning table contains all four strategies`() {
        val solutions = ReasoningVariant.entries.map { variant ->
            ReasoningSolution(
                variant = variant,
                completion = CompletionResult("Ответ", "stop", null),
            )
        }

        val table = renderReasoningComparisonTable(solutions)

        ReasoningVariant.entries.forEach { variant ->
            kotlin.test.assertContains(table, variant.tableLabel)
        }
    }
}
