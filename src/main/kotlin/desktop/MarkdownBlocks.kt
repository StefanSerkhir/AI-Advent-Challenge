package org.example.desktop

internal sealed interface MarkdownBlock {
    data class Text(val value: String) : MarkdownBlock

    data class Heading(
        val level: Int,
        val value: String,
    ) : MarkdownBlock

    data class Formula(val value: String) : MarkdownBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<MarkdownColumnAlignment>,
    ) : MarkdownBlock
}

internal enum class MarkdownColumnAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

/**
 * Splits user prompts and model output into plain text and GitHub-style Markdown tables.
 *
 * This is intentionally a small parser: the desktop UI turns tables into native
 * Compose cells while preserving everything outside a valid table as text.
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
        val trimmedLine = lines[index].trim()
        val heading = MARKDOWN_HEADING.matchEntire(trimmedLine)
        if (heading != null) {
            flushText()
            blocks += MarkdownBlock.Heading(
                level = heading.groupValues[1].length,
                value = normalizeMarkdownText(heading.groupValues[2].trim()),
            )
            index++
            continue
        }

        val inlineFormula = parseInlineFormula(trimmedLine)
        if (inlineFormula != null) {
            flushText()
            blocks += MarkdownBlock.Formula(normalizeLatexFormula(inlineFormula))
            index++
            continue
        }

        val formulaClosingFence = when (trimmedLine) {
            "\\[" -> "\\]"
            "$$" -> "$$"
            else -> null
        }
        if (formulaClosingFence != null) {
            val closingIndex = (index + 1 until lines.size).firstOrNull {
                lines[it].trim() == formulaClosingFence
            }
            if (closingIndex != null) {
                flushText()
                val formula = lines.subList(index + 1, closingIndex).joinToString("\n")
                blocks += MarkdownBlock.Formula(normalizeLatexFormula(formula))
                index = closingIndex + 1
                continue
            }
        }

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
            alignments = separators.map(::parseColumnAlignment),
        )
    }

    flushText()
    return blocks
}

internal fun normalizeMarkdownText(value: String): String = value
    .replace(MARKDOWN_LINE_BREAK, "\n")
    .replace("\\_", "_")

internal fun normalizeLatexFormula(value: String): String {
    var normalized = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString(" ") { it.trim() }
        .replace(Regex("""\\(?:begin|end)\{(?:aligned|align\*?|equation\*?)}"""), "")
        .replace("\\left", "")
        .replace("\\right", "")
        .replace("\\times", "×")
        .replace("\\cdot", "·")
        .replace("\\leq", "≤")
        .replace("\\le", "≤")
        .replace("\\geq", "≥")
        .replace("\\ge", "≥")
        .replace("\\neq", "≠")
        .replace("\\approx", "≈")
        .replace("\\rightarrow", "→")
        .replace("\\to", "→")
        .replace("\\pm", "±")
        .replace("\\infty", "∞")
        .replace("\\quad", " ")
        .replace("\\;", " ")
        .replace("\\,", " ")
        .replace("&", "")

    normalized = LATEX_TEXT.replace(normalized) { it.groupValues[1] }
    while (LATEX_FRACTION.containsMatchIn(normalized)) {
        normalized = LATEX_FRACTION.replace(normalized) {
            "(${it.groupValues[1]})/(${it.groupValues[2]})"
        }
    }
    normalized = LATEX_SQUARE_ROOT.replace(normalized) { "√(${it.groupValues[1]})" }

    return normalized
        .replace('-', '−')
        .replace(Regex("[ \\t]+"), " ")
        .trim()
}

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
private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
private val LATEX_TEXT = Regex("""\\(?:text|mathrm|mathbf)\{([^{}]*)}""")
private val LATEX_FRACTION = Regex("""\\frac\{([^{}]+)}\{([^{}]+)}""")
private val LATEX_SQUARE_ROOT = Regex("""\\sqrt\{([^{}]+)}""")

private fun parseInlineFormula(line: String): String? = when {
    line.length > 4 && line.startsWith("\\[") && line.endsWith("\\]") -> line.substring(2, line.length - 2)
    line.length > 4 && line.startsWith("$$") && line.endsWith("$$") -> line.substring(2, line.length - 2)
    else -> null
}

private fun parseColumnAlignment(separator: String): MarkdownColumnAlignment = when {
    separator.startsWith(':') && separator.endsWith(':') -> MarkdownColumnAlignment.CENTER
    separator.endsWith(':') -> MarkdownColumnAlignment.RIGHT
    else -> MarkdownColumnAlignment.LEFT
}
