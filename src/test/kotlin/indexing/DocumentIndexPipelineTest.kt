package org.example.indexing

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.*

class DocumentIndexPipelineTest {
    @Test
    fun `same corpus builds independent indexes batches embeddings and writes comparison`() = runBlocking {
        val root = createTempDirectory("document-pipeline-root")
        val output = createTempDirectory("document-pipeline-output")
        try {
            root.resolve("README.md").writeText("# Run\n\nHow to run web fixture and application. ".repeat(20))
            root.resolve("docs/ARCHITECTURE.md").also {
                it.parent.createDirectories()
                it.writeText("# Architecture\n\n## Memory layers\nSHORT_TERM WORKING LONG_TERM. ".repeat(20))
            }
            root.resolve("src/main/kotlin/network/HttpClientFactory.kt").also {
                it.parent.createDirectories()
                it.writeText("package network\n\nfun createHttpClient() = Unit\n".repeat(20))
            }
            val fake = DeterministicFakeEmbeddingClient(dimensions = 32)
            val result = DocumentIndexPipeline(
                embeddingClient = fake,
                collector = RepositoryCorpusCollector(minimumPages = 0.0),
            ).run(
                IndexingOptions(
                    corpusRoot = root,
                    outputDirectory = output,
                    fixedChunkSize = 160,
                    fixedOverlap = 20,
                    batchSize = 3,
                    evaluationQueries = List(5) { index -> EvaluationQuery("q$index", "memory layers", listOf("docs/ARCHITECTURE.md")) },
                    productionEmbeddings = false,
                ),
            )

            val fixed = JsonDocumentIndexStore(output.resolve("fixed.json")).load()
            val structured = JsonDocumentIndexStore(output.resolve("structured.json")).load()
            val comparison = JsonComparisonReportStore(output.resolve("comparison.json"), output.resolve("comparison.md")).load()
            assertEquals(fixed.corpus, structured.corpus)
            assertEquals(result.corpus.manifestHash, fixed.corpus.manifestHash)
            assertTrue(fixed.chunks.all { it.embedding.size == 32 && it.metadata.chunkId == it.chunkId })
            assertTrue(structured.chunks.all { it.embedding.size == 32 && it.metadata.source == it.source })
            assertEquals(setOf(ChunkingKind.FIXED, ChunkingKind.STRUCTURED), comparison.strategies.map { it.strategy }.toSet())
            assertFalse(comparison.evaluationUsesProductionEmbeddings)
            assertTrue(comparison.strategies.all { it.embeddingBatchCalls == (it.chunkCount + 2) / 3 })
            assertTrue(Files.size(output.resolve("comparison.md")) > 0)
        } finally {
            root.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `configured secret in source aborts before embeddings and output`() = runBlocking {
        val root = createTempDirectory("document-pipeline-secret")
        val output = root.resolve(".llm-document-index")
        val secret = "configured-api-key-value"
        try {
            root.resolve("README.md").writeText("# Accident\n$secret")
            val fake = DeterministicFakeEmbeddingClient()
            assertFailsWith<IllegalArgumentException> {
                DocumentIndexPipeline(fake, RepositoryCorpusCollector(0.0), knownSecrets = listOf(secret)).run(
                    IndexingOptions(root, output, evaluationQueries = List(5) { EvaluationQuery("$it", "q") }),
                )
            }
            assertTrue(fake.batches.isEmpty())
            assertFalse(Files.exists(output))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `CLI parses all production parameters`() {
        val parsed = IndexingCliArguments.parse(arrayOf(
            "--root=/repo", "--output", "indexes", "--strategy", "structured",
            "--fixed-chunk-size", "1400", "--overlap", "100", "--embedding-model", "custom", "--batch-size", "12",
        ))
        assertEquals(RequestedStrategies.STRUCTURED, parsed.strategies)
        assertEquals(1400, parsed.fixedChunkSize)
        assertEquals(100, parsed.overlap)
        assertEquals("custom", parsed.embeddingModel)
        assertEquals(12, parsed.batchSize)
    }
}
