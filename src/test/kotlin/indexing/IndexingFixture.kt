package org.example.indexing

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

fun main() = runBlocking {
    val output = Files.createTempDirectory("llm-document-index-fixture")
    try {
        val fake = DeterministicFakeEmbeddingClient()
        val result = DocumentIndexPipeline(fake).run(
            IndexingOptions(
                corpusRoot = Path.of("."),
                outputDirectory = output,
                productionEmbeddings = false,
            ),
        )
        val fixed = JsonDocumentIndexStore(output.resolve("fixed.json")).load()
        val structured = JsonDocumentIndexStore(output.resolve("structured.json")).load()
        val report = JsonComparisonReportStore(output.resolve("comparison.json"), output.resolve("comparison.md")).load()
        check(fixed.corpus == structured.corpus)
        check(fixed.chunks.all { it.embedding.size == 128 && it.metadata.chunkId == it.chunkId })
        check(structured.chunks.all { it.embedding.size == 128 && it.section.isNotBlank() })
        check(report.strategies.size == 2 && !report.evaluationUsesProductionEmbeddings)
        check(Files.size(output.resolve("comparison.md")) > 0)

        val stats = result.corpus.stats
        println("SAFE FIXTURE: deterministic fake embeddings; external API calls: 0")
        println("Corpus: documents=${stats.documentCount}, characters=${stats.characterCount}, words=${stats.wordCount}, pages=${rootFormat("%.2f", stats.approximatePages)}")
        println("Formula: ${stats.pageEstimateFormula}")
        println("Chunks: fixed=${fixed.chunks.size}, structured=${structured.chunks.size}, dimensions=${fixed.embedding.dimensions}")
        println("Round-trip indexes and JSON/Markdown comparison: OK")
    } finally {
        output.toFile().deleteRecursively()
    }
}
