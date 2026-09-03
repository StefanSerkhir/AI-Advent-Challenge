package org.example.desktop

internal sealed interface MarkdownBlock {
    data class Text(val value: String) : MarkdownBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : MarkdownBlock
}

/**
 * Splits model output into plain text and GitHub-style Markdown tables.
 *
 * This is intentionally a small parser: the desktop UI only needs to turn the
 * tabular evaluation returned by the temperature experiment into native Compose
 * cells. Everything outside a valid table is preserved as text.
 */
internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val textLines = mutableListOf<String>()

    fun flushText() {
        val value = textLines.joinToString("\n").trim()
        if (value.isNotEmpty()) blocks += MarkdownBlock.Text(value)
        textLines.clear()
    }

    var index = 0
    while (index < lines.size) {
        val headers = parseMarkdownRow(lines[index])
        val separators = lines.getOrNull(index + 1)?.let(::parseMarkdownRow)
        val startsTable = headers != null &&
            separators != null &&
            headers.size == separators.size &&
            separators.all { it.matches(MARKDOWN_TABLE_SEPARATOR) }

        if (!startsTable) {
            textLines += lines[index]
            index++
            continue
        }

        flushText()
        index += 2
        val rows = mutableListOf<List<String>>()
        while (index < lines.size) {
            val row = parseMarkdownRow(lines[index])
                ?.takeIf { it.size == headers.size }
                ?: break
            rows += row.map(::normalizeMarkdownText)
            index++
        }
        blocks += MarkdownBlock.Table(
            headers = headers.map(::normalizeMarkdownText),
            rows = rows,
        )
    }

    flushText()
    return blocks
}

internal fun normalizeMarkdownText(value: String): String = value
    .replace(MARKDOWN_LINE_BREAK, "\n")
    .replace("\\_", "_")

private fun parseMarkdownRow(line: String): List<String>? {
    val trimmed = line.trim()
    if ('|' !in trimmed) return null

    val content = trimmed
        .removePrefix("|")
        .removeSuffix("|")
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    while (index < content.length) {
        val character = content[index]
        when {
            character == '\\' && content.getOrNull(index + 1) == '|' -> {
                current.append('|')
                index += 2
            }

            character == '|' -> {
                cells += current.toString().trim()
                current.clear()
                index++
            }

            else -> {
                current.append(character)
                index++
            }
        }
    }
    cells += current.toString().trim()
    return cells.takeIf { it.size >= 2 }
}

private val MARKDOWN_TABLE_SEPARATOR = Regex("^:?-{3,}:?$")
private val MARKDOWN_LINE_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
