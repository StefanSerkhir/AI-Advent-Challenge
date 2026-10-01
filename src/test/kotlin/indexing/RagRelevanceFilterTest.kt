package org.example.indexing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RagRelevanceFilterTest {
    @Test
    fun `threshold is inclusive then top k is stable and ranks are rebuilt`() {
        val filtered = filterRagCandidates(
            retrieval(
                chunk("z", 0.8, rank = 9),
                chunk("b", 0.5, rank = 2),
                chunk("a", 0.5, rank = 7),
                chunk("low", 0.4999, rank = 1),
            ),
            minSimilarity = 0.5,
            resultLimit = 2,
        )

        assertEquals(listOf("z", "a"), filtered.chunks.map { it.chunkId })
        assertEquals(listOf(1, 2), filtered.chunks.map { it.rank })
        assertTrue(filtered.chunks.none { it.chunkId == "low" })
    }

    @Test
    fun `filter can return zero results and rejects invalid settings`() {
        assertTrue(filterRagCandidates(retrieval(chunk("a", 0.1)), 0.2, 5).chunks.isEmpty())
        assertFailsWith<IllegalArgumentException> { filterRagCandidates(retrieval(chunk("a", 0.1)), Double.NaN, 5) }
        assertFailsWith<IllegalArgumentException> { filterRagCandidates(retrieval(chunk("a", 0.1)), 1.1, 5) }
        assertFailsWith<IllegalArgumentException> { filterRagCandidates(retrieval(chunk("a", 0.1)), 0.0, 0) }
        assertFailsWith<IllegalArgumentException> { filterRagCandidates(retrieval(chunk("a", 0.1)), 0.0, 21) }
    }

    private fun retrieval(vararg chunks: RetrievedDocumentChunk) = DocumentRetrievalResult(
        ChunkingKind.STRUCTURED,
        "model",
        "manifest",
        chunks.toList(),
    )

    private fun chunk(id: String, score: Double, rank: Int = 1) = RetrievedDocumentChunk(
        rank, score, id, "docs/$id.md", "$id.md", "Section $id", "Text $id",
    )
}
