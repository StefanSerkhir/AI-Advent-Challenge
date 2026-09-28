package org.example.indexing

import kotlin.test.Test
import kotlin.test.assertEquals

class EvaluationTest {
    @Test
    fun `cosine search hit at three and MRR are calculated reproducibly`() {
        val base = validIndex()
        val first = base.chunks.single().copy(embedding = listOf(1f, 0f), source = "docs/a.md", metadata = ChunkMetadata("docs/a.md", "a.md", "A", "fixed-a"), chunkId = "fixed-a")
        val second = first.copy(embedding = listOf(0f, 1f), source = "docs/b.md", metadata = ChunkMetadata("docs/b.md", "b.md", "B", "fixed-b"), chunkId = "fixed-b", ordinal = 1)
        val documents = listOf(
            CorpusDocumentManifest("docs/a.md", "a.md", DocumentKind.MARKDOWN, first.documentContentHash, 7, 1),
            CorpusDocumentManifest("docs/b.md", "b.md", DocumentKind.MARKDOWN, first.documentContentHash, 7, 1),
        )
        val index = base.copy(
            corpus = base.corpus.copy(documents = documents),
            chunks = listOf(first, second),
        )
        val metrics = evaluate(
            index,
            listOf(EvaluationQuery("a", "a", listOf("docs/a.md")), EvaluationQuery("b", "b", listOf("docs/b.md"))),
            listOf(EmbeddingVector(listOf(1f, 0f)), EmbeddingVector(listOf(1f, 0.1f))),
            productionEmbeddings = false,
        )

        assertEquals(1.0, metrics.hitAt3)
        assertEquals(0.75, metrics.meanReciprocalRank)
        assertEquals(1.0, metrics.expectedSourceFoundRate)
        assertEquals(1.0, cosineSimilarity(listOf(2f, 0f), listOf(1f, 0f)))
    }
}
