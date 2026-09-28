package org.example.indexing

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class DocumentIndexStoreTest {
    @Test
    fun `versioned index round trips all metadata and vector values`() {
        val directory = createTempDirectory("document-index-store")
        val file = directory.resolve("fixed.json")
        try {
            val index = validIndex()
            JsonDocumentIndexStore(file).save(index)

            assertEquals(index, JsonDocumentIndexStore(file).load())
            assertContains(file.readText(), "\"formatVersion\": 1")
            assertContains(file.readText(), "\"chunk_id\": \"fixed-stable\"")
            assertTrue(Files.list(directory).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt and unsupported indexes do not partially load`() {
        val directory = createTempDirectory("document-index-corrupt")
        val file = directory.resolve("fixed.json")
        try {
            file.writeText("{broken")
            assertFails { JsonDocumentIndexStore(file).load() }
            file.writeText(fileJson(validIndex()).replace("\"formatVersion\": 1", "\"formatVersion\": 999"))
            assertFails { JsonDocumentIndexStore(file).load() }
            file.writeText(fileJson(validIndex()).replace("    \"formatVersion\": 1,\n", ""))
            assertFails { JsonDocumentIndexStore(file).load() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed atomic replacement preserves prior valid file and removes temporary`() {
        val directory = createTempDirectory("document-index-atomic")
        val file = directory.resolve("fixed.json")
        try {
            JsonDocumentIndexStore(file).save(validIndex())
            val previous = file.readText()
            val failing = JsonDocumentIndexStore(file) { _, _ -> error("disk failure") }

            assertFails { failing.save(validIndex().copy(chunks = validIndex().chunks.map { it.copy(text = "changed") })) }
            assertEquals(previous, file.readText())
            assertEquals("content", JsonDocumentIndexStore(file).load().chunks.single().text)
            assertTrue(Files.list(directory).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun fileJson(index: DocumentIndex): String {
        val directory = createTempDirectory("document-index-json")
        return try {
            val file = directory.resolve("index.json")
            JsonDocumentIndexStore(file).save(index)
            file.readText()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

internal fun validIndex(): DocumentIndex {
    val source = "README.md"
    val hash = sha256("content")
    val document = CorpusDocumentManifest(source, source, DocumentKind.MARKDOWN, hash, 7, 1)
    val corpus = CorpusManifest(
        documents = listOf(document),
        stats = CorpusStats(1, 7, 1, 7.0 / APPROXIMATE_CHARACTERS_PER_PAGE),
        manifestHash = sha256("$source:$hash"),
    )
    val metadata = ChunkMetadata(source, source, "Title", "fixed-stable")
    return DocumentIndex(
        strategy = ChunkingKind.FIXED,
        parameters = ChunkingParameters(1_200, 200, "test"),
        embedding = EmbeddingDescriptor("fake", "fake-v1", 2),
        corpus = corpus,
        chunks = listOf(IndexedChunk("fixed-stable", "content", listOf(1f, 0f), source, source, "Title", ChunkingKind.FIXED, 0, 0, 7, hash, metadata)),
    )
}
