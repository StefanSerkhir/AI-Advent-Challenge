package org.example.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownBlocksTest {

    @Test
    fun `parses evaluation table and converts html line breaks`() {
        val markdown = """
            **Оценка ответов**

            | Темп. | Точность | Обоснование точности |
            | ----- | -------- | -------------------- |
            | 0     | 3        | Ошибка<br>- Формат соблюдён |
            | 0.7   | 4        | risk\_summary содержит 8 слов |
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)

        assertEquals(2, blocks.size)
        assertEquals("**Оценка ответов**", assertIs<MarkdownBlock.Text>(blocks[0]).value)
        val table = assertIs<MarkdownBlock.Table>(blocks[1])
        assertEquals(listOf("Темп.", "Точность", "Обоснование точности"), table.headers)
        assertEquals(List(3) { MarkdownColumnAlignment.LEFT }, table.alignments)
        assertEquals(
            listOf(
                listOf("0", "3", "Ошибка\n- Формат соблюдён"),
                listOf("0.7", "4", "risk_summary содержит 8 слов"),
            ),
            table.rows,
        )
    }

    @Test
    fun `keeps escaped pipe inside a table cell`() {
        val markdown = """
            | Значение | Комментарий |
            | --- | --- |
            | A \| B | Не разделяет ячейку |
        """.trimIndent()

        val table = assertIs<MarkdownBlock.Table>(parseMarkdownBlocks(markdown).single())

        assertEquals(listOf("A | B", "Не разделяет ячейку"), table.rows.single())
    }

    @Test
    fun `preserves text that is not a valid table`() {
        val markdown = "Текст | с разделителем\n| но без | строки-разделителя |"

        assertEquals(listOf(MarkdownBlock.Text(markdown)), parseMarkdownBlocks(markdown))
    }

    @Test
    fun `reads left center and right alignment from separator row`() {
        val markdown = """
            | Склад | Москва | Казань | Пермь |
            | ----- | -----: | :----: | ----: |
            | A     |      4 |   6    |     9 |
        """.trimIndent()

        val table = assertIs<MarkdownBlock.Table>(parseMarkdownBlocks(markdown).single())

        assertEquals(
            listOf(
                MarkdownColumnAlignment.LEFT,
                MarkdownColumnAlignment.RIGHT,
                MarkdownColumnAlignment.CENTER,
                MarkdownColumnAlignment.RIGHT,
            ),
            table.alignments,
        )
    }

    @Test
    fun `parses heading without hash markers`() {
        val block = parseMarkdownBlocks(
            "## 1–5. Оптимальный план при ограничении B→Пермь ≤ 30",
        ).single()

        assertEquals(
            MarkdownBlock.Heading(
                level = 2,
                value = "1–5. Оптимальный план при ограничении B→Пермь ≤ 30",
            ),
            block,
        )
    }

    @Test
    fun `parses and normalizes bracketed and dollar formulas`() {
        val markdown = """
            До формулы.

            \[
            620-x+2y+6z=540+3y+7z.
            \]

            ${'$'}${'$'} a \le b \times 2 ${'$'}${'$'}
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)

        assertEquals("До формулы.", assertIs<MarkdownBlock.Text>(blocks[0]).value)
        assertEquals(
            "620−x+2y+6z=540+3y+7z.",
            assertIs<MarkdownBlock.Formula>(blocks[1]).value,
        )
        assertEquals("a ≤ b × 2", assertIs<MarkdownBlock.Formula>(blocks[2]).value)
    }

    @Test
    fun `preserves an unclosed formula fence as text`() {
        val markdown = "Текст\n\\[\nформула без конца"

        assertEquals(listOf(MarkdownBlock.Text(markdown)), parseMarkdownBlocks(markdown))
    }
}
