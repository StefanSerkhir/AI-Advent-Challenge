package org.example.indexing

import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DocumentIndexPersistenceException(message: String, cause: Throwable? = null) : IOException(message, cause)

class JsonDocumentIndexStore(
    private val file: Path,
    private val replace: (Path, Path) -> Unit = ::atomicReplace,
) {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    @Synchronized
    fun load(): DocumentIndex {
        val content = try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw DocumentIndexPersistenceException("Не удалось прочитать индекс ${file.fileName}", error)
        }
        val index = try {
            val element = json.parseToJsonElement(content)
            val version = element.jsonObject["formatVersion"]?.jsonPrimitive?.int
                ?: throw IllegalArgumentException("Missing document index format version")
            require(version == DOCUMENT_INDEX_FORMAT_VERSION) { "Unsupported document index format version" }
            json.decodeFromJsonElement<DocumentIndex>(element)
        } catch (error: Exception) {
            throw DocumentIndexPersistenceException("Индекс ${file.fileName} повреждён или имеет неизвестную схему", error)
        }
        validateIndex(index)
        return index
    }

    @Synchronized
    fun save(index: DocumentIndex) {
        validateIndex(index)
        val target = file.toAbsolutePath()
        var temporary: Path? = null
        try {
            Files.createDirectories(target.parent)
            temporary = Files.createTempFile(target.parent, ".document-index-", ".tmp")
            Files.writeString(temporary, json.encodeToString(index), StandardCharsets.UTF_8)
            replace(temporary, target)
        } catch (error: Exception) {
            throw DocumentIndexPersistenceException("Не удалось атомарно сохранить индекс ${file.fileName}", error)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun validateIndex(index: DocumentIndex) {
        require(index.formatVersion == DOCUMENT_INDEX_FORMAT_VERSION) { "Unsupported document index format version" }
        require(index.parameters.targetSize > 0 && index.parameters.overlap in 0 until index.parameters.targetSize) {
            "Invalid chunking parameters"
        }
        require(index.embedding.provider.isNotBlank() && index.embedding.model.isNotBlank()) { "Missing embedding descriptor" }
        require(index.embedding.dimensions > 0) { "Embedding dimensions must be positive" }
        require(index.corpus.documents.isNotEmpty()) { "Corpus manifest is empty" }
        require(index.corpus.documents.map { it.source }.distinct().size == index.corpus.documents.size) { "Duplicate corpus source" }
        val manifestCharacters = index.corpus.documents.sumOf { it.characterCount.toLong() }
        val manifestWords = index.corpus.documents.sumOf { it.wordCount.toLong() }
        require(index.corpus.stats.documentCount == index.corpus.documents.size) { "Corpus document count is inconsistent" }
        require(index.corpus.stats.characterCount == manifestCharacters && index.corpus.stats.wordCount == manifestWords) {
            "Corpus aggregate counts are inconsistent"
        }
        require(index.corpus.stats.approximatePages == manifestCharacters.toDouble() / APPROXIMATE_CHARACTERS_PER_PAGE) {
            "Corpus page estimate is inconsistent"
        }
        require(index.corpus.manifestHash == sha256(index.corpus.documents.joinToString("\n") { "${it.source}:${it.contentHash}" })) {
            "Corpus manifest hash is inconsistent"
        }
        require(index.chunks.isNotEmpty()) { "Document index contains no chunks" }
        require(index.chunks.map { it.chunkId }.distinct().size == index.chunks.size) { "Duplicate chunk id" }
        index.corpus.documents.forEach {
            require(it.source.isRelativeIndexPath()) { "Corpus source must be relative" }
            require(it.title.isNotBlank() && it.characterCount > 0 && it.wordCount >= 0) { "Invalid corpus document metadata" }
        }
        val manifest = index.corpus.documents.associateBy(CorpusDocumentManifest::source)
        index.chunks.forEach { chunk ->
            require(chunk.strategy == index.strategy) { "Chunk strategy does not match index strategy" }
            require(chunk.text.isNotBlank()) { "Index contains an empty chunk" }
            require(chunk.source.isRelativeIndexPath()) { "Chunk source must be relative" }
            require(chunk.embedding.size == index.embedding.dimensions) { "Embedding dimensions do not match index descriptor" }
            require(chunk.embedding.all(Float::isFinite)) { "Embedding contains a non-finite value" }
            require(chunk.ordinal >= 0) { "Chunk ordinal must be non-negative" }
            val sourceDocument = manifest[chunk.source] ?: error("Chunk source is absent from corpus manifest")
            require(chunk.startOffset >= 0 && chunk.endOffset > chunk.startOffset && chunk.endOffset <= sourceDocument.characterCount) {
                "Invalid chunk offsets"
            }
            require(chunk.text.length == chunk.endOffset - chunk.startOffset) { "Chunk text and offsets are inconsistent" }
            require(chunk.title == sourceDocument.title) { "Chunk title does not match corpus manifest" }
            require(chunk.metadata == ChunkMetadata(chunk.source, chunk.title, chunk.section, chunk.chunkId)) { "Invalid required chunk metadata" }
            require(sourceDocument.contentHash == chunk.documentContentHash) { "Chunk document hash does not match corpus manifest" }
        }
    }
}

class JsonComparisonReportStore(
    private val jsonFile: Path,
    private val markdownFile: Path,
    private val replace: (Path, Path) -> Unit = ::atomicReplace,
) {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    fun save(report: ChunkingComparisonReport) {
        require(report.formatVersion == COMPARISON_FORMAT_VERSION)
        writeAtomically(jsonFile, json.encodeToString(report))
        writeAtomically(markdownFile, report.toMarkdown())
    }

    fun load(): ChunkingComparisonReport {
        val report = try {
            val element = json.parseToJsonElement(Files.readString(jsonFile, StandardCharsets.UTF_8))
            val version = element.jsonObject["formatVersion"]?.jsonPrimitive?.int
                ?: throw IllegalArgumentException("Missing comparison report format version")
            require(version == COMPARISON_FORMAT_VERSION) { "Unsupported comparison report format version" }
            json.decodeFromJsonElement<ChunkingComparisonReport>(element)
        } catch (error: Exception) {
            throw DocumentIndexPersistenceException("Comparison report повреждён или имеет неизвестную схему", error)
        }
        require(report.formatVersion == COMPARISON_FORMAT_VERSION) { "Unsupported comparison report format version" }
        return report
    }

    private fun writeAtomically(file: Path, content: String) {
        val target = file.toAbsolutePath()
        var temporary: Path? = null
        try {
            Files.createDirectories(target.parent)
            temporary = Files.createTempFile(target.parent, ".document-report-", ".tmp")
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            replace(temporary, target)
        } catch (error: Exception) {
            throw DocumentIndexPersistenceException("Не удалось атомарно сохранить ${file.fileName}", error)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

private fun atomicReplace(temporary: Path, target: Path) {
    try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
