package org.example.indexing

import kotlin.test.*

class ChunkingTest {
    @Test
    fun `fixed chunking honors overlap boundaries unicode and stable ids`() {
        val text = (1..45).joinToString(" ") { "слово$it🙂" } + "\nпоследняя строка"
        val document = document("docs/unicode.md", DocumentKind.MARKDOWN, text)
        val strategy = FixedSizeChunkingStrategy(chunkSize = 120, overlap = 24)

        val first = strategy.chunk(listOf(document))
        val repeated = strategy.chunk(listOf(document))

        assertTrue(first.size > 2)
        assertEquals(first.map(ChunkDraft::chunkId), repeated.map(ChunkDraft::chunkId))
        assertTrue(first.all { it.text.isNotBlank() && it.text.length <= 120 })
        assertTrue(first.zipWithNext().all { (left, right) -> right.startOffset < left.endOffset })
        assertTrue(first.all { it.text.hasValidSurrogates() })
        assertTrue(first.all { it.source == "docs/unicode.md" })
        assertNotEquals(first[0].chunkId, first[1].chunkId)
    }

    @Test
    fun `markdown hierarchy is retained and long section is bounded`() {
        val longBody = (1..150).joinToString(" ") { "деталь$it" }
        val document = document(
            "docs/guide.md",
            DocumentKind.MARKDOWN,
            "# Root\nВведение\n\n## Child\n$longBody\n\n### Leaf\nФинал",
        )
        val chunks = StructureAwareChunkingStrategy(targetSize = 250, overlap = 30).chunk(listOf(document))

        assertTrue(chunks.any { it.section == "Root" })
        assertTrue(chunks.any { it.section == "Root > Child" })
        assertTrue(chunks.any { it.section == "Root > Child > Leaf" })
        assertTrue(chunks.all { it.text.length <= 250 && it.text.isNotBlank() })
        assertTrue(chunks.count { it.section == "Root > Child" } > 1)
    }

    @Test
    fun `code declarations and files remain separate`() {
        val alpha = document(
            "src/main/kotlin/Alpha.kt",
            DocumentKind.KOTLIN,
            "package demo\n\nclass Alpha {\n    fun run() = 1\n}\n\nfun helper() = 2",
        )
        val beta = document(
            "frontend/src/beta.ts",
            DocumentKind.TYPESCRIPT,
            "export interface Beta { value: string }\n\nexport function createBeta() { return { value: 'b' } }",
        )
        val chunks = StructureAwareChunkingStrategy(targetSize = 300).chunk(listOf(alpha, beta))

        assertEquals(setOf(alpha.source, beta.source), chunks.map(ChunkDraft::source).toSet())
        assertTrue(chunks.all { chunk -> chunk.text !in listOf(alpha.text + beta.text, beta.text + alpha.text) })
        assertTrue(chunks.any { it.source == alpha.source && it.section.contains("Alpha") })
        assertTrue(chunks.any { it.source == beta.source && it.section.contains("Beta") })
        assertFalse(chunks.any { it.text.isBlank() })
    }

    @Test
    fun `required metadata inputs are present on every draft`() {
        val document = document("README.md", DocumentKind.MARKDOWN, "# Title\n\nUseful content")
        val chunk = StructureAwareChunkingStrategy().chunk(listOf(document)).single()

        assertEquals("README.md", chunk.source)
        assertEquals("README.md", chunk.title)
        assertContains(chunk.section, "Title")
        assertTrue(chunk.chunkId.startsWith("structured-"))
        assertEquals(document.contentHash, chunk.documentContentHash)
        assertTrue(chunk.endOffset > chunk.startOffset)
    }

    private fun document(source: String, kind: DocumentKind, raw: String): NormalizedDocument {
        val text = normalizeText(raw)
        return NormalizedDocument(source, source.substringAfterLast('/'), kind, text, sha256(text))
    }

    private fun String.hasValidSurrogates(): Boolean = indices.all { index ->
        when {
            Character.isHighSurrogate(this[index]) -> index + 1 < length && Character.isLowSurrogate(this[index + 1])
            Character.isLowSurrogate(this[index]) -> index > 0 && Character.isHighSurrogate(this[index - 1])
            else -> true
        }
    }
}
