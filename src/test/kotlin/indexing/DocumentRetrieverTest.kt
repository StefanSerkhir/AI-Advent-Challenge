package org.example.indexing

import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class DocumentRetrieverTest {
    @Test
    fun `retriever returns stable top five with metadata and reuses cosine ranking`() = runBlocking {
        val directory = Files.createTempDirectory("document-retriever")
        try {
            val index = testIndex(
                (1..6).map { number ->
                    val id = if (number == 1) "chunk-b" else if (number == 2) "chunk-a" else "chunk-$number"
                    id to if (number <= 2) listOf(1f, 0f) else listOf(1f, number.toFloat() / 10f)
                },
            )
            val file = directory.resolve("structured.json")
            JsonDocumentIndexStore(file).save(index)
            val retriever = DocumentRetriever(JsonDocumentIndexStore(file)) { descriptor ->
                assertEquals("test-model", descriptor.model)
                embeddingClient(listOf(1f, 0f))
            }

            val result = retriever.retrieve("question")

            assertEquals(5, result.chunks.size)
            assertEquals(listOf(1, 2, 3, 4, 5), result.chunks.map { it.rank })
            assertEquals(listOf("chunk-a", "chunk-b"), result.chunks.take(2).map { it.chunkId })
            assertEquals("docs/chunk-a.md", result.chunks.first().source)
            assertEquals("Section chunk-a", result.chunks.first().section)
            assertTrue(result.chunks.all { it.text.isNotBlank() && it.score.isFinite() })
            assertEquals(index.corpus.manifestHash, result.manifestHash)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `retriever rejects missing corrupt incompatible and wrong dimension indexes safely`() = runBlocking {
        val directory = Files.createTempDirectory("document-retriever-errors")
        try {
            val missing = DocumentRetriever(JsonDocumentIndexStore(directory.resolve("missing.json"))) { embeddingClient() }
            assertContains(assertFailsWith<DocumentRetrievalException> { missing.retrieve("question") }.message.orEmpty(), "buildDocumentIndexes")

            val corruptFile = directory.resolve("corrupt.json")
            Files.writeString(corruptFile, "{not-json")
            val corrupt = DocumentRetriever(JsonDocumentIndexStore(corruptFile)) { embeddingClient() }
            assertContains(assertFailsWith<DocumentRetrievalException> { corrupt.retrieve("question") }.message.orEmpty(), "повреждён")

            val validFile = directory.resolve("valid.json")
            JsonDocumentIndexStore(validFile).save(testIndex((1..5).map { "chunk-$it" to listOf(1f, 0f) }))
            val incompatible = DocumentRetriever(JsonDocumentIndexStore(validFile)) {
                object : EmbeddingClient {
                    override val provider = "other"
                    override val model = "other-model"
                    override suspend fun embed(texts: List<String>) = listOf(EmbeddingVector(listOf(1f, 0f)))
                }
            }
            assertContains(assertFailsWith<DocumentRetrievalException> { incompatible.retrieve("question") }.message.orEmpty(), "Индекс создан")

            val wrongDimensions = DocumentRetriever(JsonDocumentIndexStore(validFile)) { embeddingClient(listOf(1f, 0f, 0f)) }
            assertContains(assertFailsWith<DocumentRetrievalException> { wrongDimensions.retrieve("question") }.message.orEmpty(), "Размерность")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `query embedding cancellation is cooperative`() = runBlocking {
        val directory = Files.createTempDirectory("document-retriever-cancel")
        try {
            val file = directory.resolve("structured.json")
            JsonDocumentIndexStore(file).save(testIndex((1..5).map { "chunk-$it" to listOf(1f, 0f) }))
            val retriever = DocumentRetriever(JsonDocumentIndexStore(file)) {
                object : EmbeddingClient {
                    override val provider = "test"
                    override val model = "test-model"
                    override suspend fun embed(texts: List<String>): List<EmbeddingVector> = awaitCancellation()
                }
            }
            val job = async { retriever.retrieve("question") }
            yield()
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun embeddingClient(vector: List<Float> = listOf(1f, 0f)) = object : EmbeddingClient {
        override val provider = "test"
        override val model = "test-model"
        override suspend fun embed(texts: List<String>) = texts.map { EmbeddingVector(vector) }
    }

    private fun testIndex(entries: List<Pair<String, List<Float>>>): DocumentIndex {
        val documents = entries.map { (id, _) ->
            val text = "Text for $id"
            CorpusDocumentManifest(
                source = "docs/$id.md",
                title = "$id.md",
                kind = DocumentKind.MARKDOWN,
                contentHash = sha256(text),
                characterCount = text.length,
                wordCount = countWords(text),
            )
        }
        val characters = documents.sumOf { it.characterCount.toLong() }
        val manifest = CorpusManifest(
            documents = documents,
            stats = CorpusStats(
                documents.size,
                characters,
                documents.sumOf { it.wordCount.toLong() },
                characters.toDouble() / APPROXIMATE_CHARACTERS_PER_PAGE,
            ),
            manifestHash = sha256(documents.joinToString("\n") { "${it.source}:${it.contentHash}" }),
        )
        return DocumentIndex(
            strategy = ChunkingKind.STRUCTURED,
            parameters = ChunkingParameters(1600, 0, "test"),
            embedding = EmbeddingDescriptor("test", "test-model", 2),
            corpus = manifest,
            chunks = entries.mapIndexed { index, (id, vector) ->
                val document = documents[index]
                val text = "Text for $id"
                IndexedChunk(
                    id, text, vector, document.source, document.title, "Section $id",
                    ChunkingKind.STRUCTURED, 0, 0, text.length, document.contentHash,
                    ChunkMetadata(document.source, document.title, "Section $id", id),
                )
            },
        )
    }
}
