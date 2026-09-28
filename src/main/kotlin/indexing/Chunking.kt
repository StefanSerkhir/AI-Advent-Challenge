package org.example.indexing

import kotlin.math.max

interface ChunkingStrategy {
    val kind: ChunkingKind
    val parameters: ChunkingParameters
    fun chunk(documents: List<NormalizedDocument>): List<ChunkDraft>
}

class FixedSizeChunkingStrategy(
    private val chunkSize: Int = DEFAULT_FIXED_CHUNK_SIZE,
    private val overlap: Int = DEFAULT_FIXED_OVERLAP,
) : ChunkingStrategy {
    init {
        require(chunkSize >= 100) { "Размер fixed-чанка должен быть не меньше 100 символов" }
        require(overlap >= 0 && overlap < chunkSize) { "Overlap должен быть от 0 до chunkSize - 1" }
    }

    override val kind = ChunkingKind.FIXED
    override val parameters = ChunkingParameters(chunkSize, overlap, "newline_then_word_unicode_safe")

    override fun chunk(documents: List<NormalizedDocument>): List<ChunkDraft> =
        documents.flatMap { document ->
            splitRange(document.text, 0, document.text.length, chunkSize, overlap).mapIndexedNotNull { ordinal, range ->
                range.toDraft(document, section = "", strategy = kind, ordinal = ordinal)
            }
        }
}

class StructureAwareChunkingStrategy(
    private val targetSize: Int = DEFAULT_STRUCTURED_CHUNK_SIZE,
    private val overlap: Int = 120,
) : ChunkingStrategy {
    init {
        require(targetSize >= 200) { "Размер structured-чанка должен быть не меньше 200 символов" }
        require(overlap >= 0 && overlap < targetSize) { "Overlap должен быть от 0 до targetSize - 1" }
    }

    override val kind = ChunkingKind.STRUCTURED
    override val parameters = ChunkingParameters(targetSize, overlap, "headings_or_declarations_then_bounded_parts")

    override fun chunk(documents: List<NormalizedDocument>): List<ChunkDraft> = documents.flatMap { document ->
        val blocks = when (document.kind) {
            DocumentKind.MARKDOWN -> markdownBlocks(document.text)
            DocumentKind.KOTLIN, DocumentKind.TYPESCRIPT, DocumentKind.TSX -> codeBlocks(document.text, document.kind)
            DocumentKind.TEXT -> paragraphBlocks(document.text)
        }
        val ranges = blocks.flatMap { block ->
            if (block.end - block.start <= targetSize) listOf(block)
            else splitRange(document.text, block.start, block.end, targetSize, overlap)
                .map { Block(it.first, it.last + 1, block.section) }
        }
        ranges.mapIndexedNotNull { ordinal, block ->
            (block.start until block.end).toDraft(document, block.section, kind, ordinal)
        }
    }

    private fun markdownBlocks(text: String): List<Block> {
        val headings = mutableListOf<Heading>()
        var offset = 0
        text.splitToSequence('\n').forEach { line ->
            MARKDOWN_HEADING.matchEntire(line)?.let { match ->
                headings += Heading(offset, match.groupValues[1].length, match.groupValues[2].trim())
            }
            offset += line.length + 1
        }
        if (headings.isEmpty()) return paragraphBlocks(text)

        val result = mutableListOf<Block>()
        if (headings.first().offset > 0 && text.substring(0, headings.first().offset).isNotBlank()) {
            result += Block(0, headings.first().offset, "Preamble")
        }
        val path = mutableListOf<String>()
        headings.forEachIndexed { index, heading ->
            while (path.size >= heading.level) path.removeLast()
            path += heading.title
            val end = headings.getOrNull(index + 1)?.offset ?: text.length
            result += Block(heading.offset, end, path.joinToString(" > "))
        }
        return result.filter { text.substring(it.start, it.end).isNotBlank() }
    }

    private fun codeBlocks(text: String, kind: DocumentKind): List<Block> {
        val declarationPattern = if (kind == DocumentKind.KOTLIN) KOTLIN_DECLARATION else TYPESCRIPT_DECLARATION
        val declarations = mutableListOf<Pair<Int, String>>()
        var offset = 0
        text.splitToSequence('\n').forEach { line ->
            declarationPattern.find(line)?.let { match ->
                declarations += offset to match.groupValues.last { it.isNotBlank() }.trim()
            }
            offset += line.length + 1
        }
        if (declarations.isEmpty()) return listOf(Block(0, text.length, "File: declarations"))
        val blocks = mutableListOf<Block>()
        val firstOffset = declarations.first().first
        if (firstOffset > 0 && text.substring(0, firstOffset).isNotBlank()) blocks += Block(0, firstOffset, "File preamble")
        declarations.forEachIndexed { index, (start, name) ->
            blocks += Block(start, declarations.getOrNull(index + 1)?.first ?: text.length, "Declaration: $name")
        }
        return blocks.filter { text.substring(it.start, it.end).isNotBlank() }
    }

    private fun paragraphBlocks(text: String): List<Block> {
        val result = mutableListOf<Block>()
        PARAGRAPH.findAll(text).forEachIndexed { index, match ->
            result += Block(match.range.first, match.range.last + 1, "Section ${index + 1}")
        }
        return result.ifEmpty { listOf(Block(0, text.length, "Document")) }
    }

    private data class Heading(val offset: Int, val level: Int, val title: String)
    private data class Block(val start: Int, val end: Int, val section: String)

    companion object {
        private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
        private val KOTLIN_DECLARATION = Regex("^\\s*(?:(?:public|private|internal|protected|data|sealed|enum|annotation|value|suspend|inline|tailrec|operator|infix|open|abstract|final)\\s+)*(?:class|interface|object|fun|typealias)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        private val TYPESCRIPT_DECLARATION = Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?(?:class|interface|type|enum|function|const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
        private val PARAGRAPH = Regex("(?s)\\S(?:.*?\\S)?(?=\\n\\s*\\n|\\z)")
    }
}

private fun IntRange.toDraft(
    document: NormalizedDocument,
    section: String,
    strategy: ChunkingKind,
    ordinal: Int,
): ChunkDraft? {
    if (isEmpty()) return null
    var start = first
    var end = last + 1
    while (start < end && document.text[start].isWhitespace()) start++
    while (end > start && document.text[end - 1].isWhitespace()) end--
    if (start >= end) return null
    val text = document.text.substring(start, end)
    return ChunkDraft(
        chunkId = stableChunkId(strategy, document, section, ordinal, start, end, text),
        text = text,
        source = document.source,
        title = document.title,
        section = section,
        strategy = strategy,
        ordinal = ordinal,
        startOffset = start,
        endOffset = end,
        documentContentHash = document.contentHash,
    )
}

private fun splitRange(text: String, rangeStart: Int, rangeEnd: Int, targetSize: Int, overlap: Int): List<IntRange> {
    if (rangeStart >= rangeEnd) return emptyList()
    val result = mutableListOf<IntRange>()
    var start = safeStartOffset(text, rangeStart)
    while (start < rangeEnd) {
        val hardEnd = safeEndOffset(text, minOf(start + targetSize, rangeEnd))
        var end = hardEnd
        if (hardEnd < rangeEnd) {
            val preferredFloor = start + max(1, targetSize * 3 / 5)
            val newline = text.lastIndexOf('\n', hardEnd - 1).takeIf { it >= preferredFloor }?.plus(1)
            val whitespace = (hardEnd - 1 downTo preferredFloor).firstOrNull { text[it].isWhitespace() }?.plus(1)
            end = safeEndOffset(text, newline ?: whitespace ?: hardEnd)
        }
        if (end <= start) end = safeEndOffset(text, minOf(start + targetSize, rangeEnd))
        if (text.substring(start, end).isNotBlank()) result += start until end
        if (end >= rangeEnd) break
        val next = safeStartOffset(text, max(start + 1, end - overlap))
        start = if (next > start) next else safeStartOffset(text, end)
    }
    return result
}
