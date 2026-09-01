package org.example.app

data class ResponseMetrics(
    val run: String,
    val characterCount: Int,
    val wordCount: Int,
    val completionTokens: Int?,
    val finishReason: String?,
)

fun LabeledResponse.metrics(): ResponseMetrics = ResponseMetrics(
    run = variant.tableLabel,
    characterCount = content.codePointCount(0, content.length),
    wordCount = Regex("\\S+").findAll(content).count(),
    completionTokens = completion.usage?.completionTokens,
    finishReason = completion.finishReason,
)

fun renderComparisonTable(responses: List<LabeledResponse>): String {
    val headers = listOf("прогон", "символов", "слов", "токенов", "finish_reason")
    val rows = responses.map(LabeledResponse::metrics).map { metrics ->
        listOf(
            metrics.run,
            metrics.characterCount.toString(),
            metrics.wordCount.toString(),
            metrics.completionTokens?.toString() ?: "н/д",
            metrics.finishReason ?: "н/д",
        )
    }
    val widths = headers.indices.map { column ->
        maxOf(headers[column].length, rows.maxOfOrNull { it[column].length } ?: 0)
    }

    fun border(left: String, middle: String, right: String): String = widths.joinToString(
        separator = middle,
        prefix = left,
        postfix = right,
    ) { "─".repeat(it + 2) }

    fun row(values: List<String>, numericColumns: Set<Int> = emptySet()): String =
        values.mapIndexed { index, value ->
            val aligned = if (index in numericColumns) {
                value.padStart(widths[index])
            } else {
                value.padEnd(widths[index])
            }
            " $aligned "
        }.joinToString(separator = "│", prefix = "│", postfix = "│")

    return buildString {
        appendLine(border("┌", "┬", "┐"))
        appendLine(row(headers))
        appendLine(border("├", "┼", "┤"))
        rows.forEach { appendLine(row(it, numericColumns = setOf(1, 2, 3))) }
        append(border("└", "┴", "┘"))
    }
}
