package org.example.indexing

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

class CorpusTooSmallException(stats: CorpusStats, minimumPages: Double) : IllegalArgumentException(
    "Корпус слишком мал: ${rootFormat("%.2f", stats.approximatePages)} стр. по формуле ${stats.pageEstimateFormula}; " +
        "требуется не менее ${rootFormat("%.2f", minimumPages)} стр. Индекс не создан.",
)

class RepositoryCorpusCollector(
    private val minimumPages: Double = DEFAULT_MINIMUM_CORPUS_PAGES,
    private val pdfTextExtractor: PdfTextExtractor = PdfTextExtractor(),
) {
    init {
        require(minimumPages >= 0.0 && minimumPages.isFinite()) { "Минимальный объём corpus должен быть конечным неотрицательным числом" }
    }

    fun collect(root: Path): CollectedCorpus {
        val absoluteRoot = root.toAbsolutePath().normalize()
        require(Files.isDirectory(absoluteRoot)) { "Корень корпуса не является каталогом: $root" }

        val paths = Files.walk(absoluteRoot).use { stream ->
            stream.filter { !Files.isSymbolicLink(it) && Files.isRegularFile(it) }
                .map { absoluteRoot.relativize(it).toString().replace('\\', '/') }
                .filter(::isIncludedSource)
                .filter { !isExcludedPath(it) }
                .sorted()
                .toList()
        }
        val documents = paths.map { source -> readDocument(absoluteRoot, source) }
        val manifests = documents.map { document ->
            CorpusDocumentManifest(
                source = document.source,
                title = document.title,
                kind = document.kind,
                contentHash = document.contentHash,
                characterCount = document.text.length,
                wordCount = countWords(document.text),
            )
        }
        val characterCount = manifests.sumOf { it.characterCount.toLong() }
        val stats = CorpusStats(
            documentCount = documents.size,
            characterCount = characterCount,
            wordCount = manifests.sumOf { it.wordCount.toLong() },
            approximatePages = characterCount.toDouble() / APPROXIMATE_CHARACTERS_PER_PAGE,
        )
        if (stats.approximatePages < minimumPages) throw CorpusTooSmallException(stats, minimumPages)
        val manifestHash = sha256(manifests.joinToString("\n") { "${it.source}:${it.contentHash}" })
        return CollectedCorpus(documents, CorpusManifest(documents = manifests, stats = stats, manifestHash = manifestHash))
    }

    private fun readDocument(root: Path, source: String): NormalizedDocument {
        val file = root.resolve(source).normalize()
        require(file.startsWith(root)) { "Файл корпуса вышел за пределы root" }
        val kind = kindFor(file)
        val text = if (kind == DocumentKind.PDF) {
            normalizeText(pdfTextExtractor.extract(file, source))
        } else {
            readUtf8Text(file, source)
        }
        require(text.isNotBlank()) { "Пустой документ не может входить в корпус: $source" }
        return NormalizedDocument(
            source = source,
            title = file.name,
            kind = kind,
            text = text,
            contentHash = sha256(text),
        )
    }

    private fun readUtf8Text(file: Path, source: String): String {
        val bytes = Files.readAllBytes(file)
        require(bytes.none { it == 0.toByte() }) { "Бинарный файл не может входить в корпус: $source" }
        val raw = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Файл корпуса не является корректным UTF-8: $source", error)
        }
        return normalizeText(raw)
    }

    private fun isIncludedSource(source: String): Boolean = when {
        source == "README.md" -> true
        source.startsWith("docs/") && (source.endsWith(".md", ignoreCase = true) || source.endsWith(".pdf", ignoreCase = true)) -> true
        source.startsWith("src/main/kotlin/") && source.endsWith(".kt", ignoreCase = true) -> true
        source.startsWith("frontend/src/") && (source.endsWith(".ts", true) || source.endsWith(".tsx", true)) -> true
        else -> false
    }

    private fun isExcludedPath(source: String): Boolean {
        val segments = source.split('/')
        return segments.any { segment ->
            segment in setOf(
                ".git", "build", "generated", "generated-sources", "node_modules", "dist", "out",
                "test-results", "playwright-report", "tmp", "temp",
            ) ||
                segment.startsWith(".llm-")
        } || source == ".env" || source.startsWith("legacy-desktop/") || ".generated." in source
    }

    private fun kindFor(file: Path): DocumentKind = when (file.extension.lowercase()) {
        "md" -> DocumentKind.MARKDOWN
        "pdf" -> DocumentKind.PDF
        "kt" -> DocumentKind.KOTLIN
        "tsx" -> DocumentKind.TSX
        "ts" -> DocumentKind.TYPESCRIPT
        else -> DocumentKind.TEXT
    }
}
