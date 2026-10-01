package org.example.indexing

/** Pure request-local second-stage selection. It never reads or mutates the persistent index. */
fun filterRagCandidates(
    retrieval: DocumentRetrievalResult,
    minSimilarity: Double,
    resultLimit: Int,
): DocumentRetrievalResult {
    require(minSimilarity.isFinite() && minSimilarity in -1.0..1.0) {
        "Similarity threshold должен быть конечным числом от -1.0 до 1.0"
    }
    require(resultLimit in 1..20) { "Result limit должен быть от 1 до 20" }

    val selected = retrieval.chunks
        .asSequence()
        .filter { it.score >= minSimilarity }
        .sortedWith(compareByDescending<RetrievedDocumentChunk> { it.score }.thenBy { it.chunkId })
        .take(resultLimit)
        .mapIndexed { index, chunk -> chunk.copy(rank = index + 1) }
        .toList()
    return retrieval.copy(chunks = selected)
}
