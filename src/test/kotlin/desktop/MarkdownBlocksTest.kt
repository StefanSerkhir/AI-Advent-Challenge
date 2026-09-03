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
}
