package org.example.indexing

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.*
import kotlin.math.ceil

private val WORD_PATTERN = Regex("[\\p{L}\\p{N}_]+(?:[-'][\\p{L}\\p{N}_]+)*")

internal fun normalizeText(raw: String): String {
    val withoutBom = raw.removePrefix("\uFEFF")
    val normalizedLines = withoutBom
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trimEnd()
    return Normalizer.normalize(normalizedLines, Normalizer.Form.NFC)
}

internal fun countWords(text: String): Int = WORD_PATTERN.findAll(text).count()

internal fun approximateTokens(characters: Long): Long = ceil(characters / 4.0).toLong()

internal fun rootFormat(pattern: String, vararg values: Any): String = String.format(Locale.ROOT, pattern, *values)

internal fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return bytes.joinToString("") { rootFormat("%02x", it) }
}

internal fun stableChunkId(
    strategy: ChunkingKind,
    document: NormalizedDocument,
    section: String,
    ordinal: Int,
    startOffset: Int,
    endOffset: Int,
    chunkText: String,
): String = "${strategy.wireName}-${sha256(listOf(
    strategy.wireName,
    document.source,
    section,
    ordinal,
    startOffset,
    endOffset,
    document.contentHash,
    sha256(chunkText),
).joinToString("\n")).take(24)}"

internal fun String.isRelativeIndexPath(): Boolean =
    isNotBlank() && !startsWith('/') && !startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(this) &&
        split('/', '\\').none { it == ".." }

internal fun safeEndOffset(text: String, candidate: Int): Int {
    var end = candidate.coerceIn(0, text.length)
    if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
        end--
    }
    return end
}

internal fun safeStartOffset(text: String, candidate: Int): Int {
    var start = candidate.coerceIn(0, text.length)
    if (start in 1 until text.length && Character.isHighSurrogate(text[start - 1]) && Character.isLowSurrogate(text[start])) {
        start++
    }
    return start
}
