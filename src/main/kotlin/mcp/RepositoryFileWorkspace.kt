package org.example.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

internal const val PROJECT_SEARCH_TOOL = "project_search"
internal const val PROJECT_READ_FILES_TOOL = "project_read_files"
internal const val PROJECT_WRITE_FILE_TOOL = "project_write_file"

/** Repository-scoped text-file operations used by the workspace MCP server. */
internal class RepositoryFileWorkspace(repositoryRoot: Path) {
    private val root = repositoryRoot.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Настроенный корень репозитория недоступен."
        }
    }

    fun search(query: String): JsonObject {
        require(query.isNotBlank()) { "query не должен быть пустым." }
        require(query.length <= MAX_QUERY_LENGTH) { "query превышает допустимую длину." }
        require(query.none(Char::isISOControl)) { "query содержит недопустимые символы." }

        val candidates = collectSearchCandidates()
        val matches = mutableListOf<SearchHit>()
        var scannedFiles = 0
        var matchedFiles = 0
        var totalMatches = 0
        var scannedBytes = 0L
        var truncated = candidates.size > MAX_SEARCH_FILES
        for (relativeText in candidates.take(MAX_SEARCH_FILES)) {
            val resolved = resolveExistingFile(relativeText)
            val size = Files.size(resolved)
            if (size > MAX_SEARCH_FILE_BYTES || scannedBytes + size > MAX_SEARCH_TOTAL_BYTES) {
                truncated = true
                continue
            }
            val content = readUtf8(resolved, MAX_SEARCH_FILE_BYTES)
            scannedFiles++
            scannedBytes += size
            var fileMatched = false
            content.lineSequence().forEachIndexed { index, line ->
                var fromIndex = 0
                while (true) {
                    val found = line.indexOf(query, fromIndex)
                    if (found < 0) break
                    totalMatches++
                    fileMatched = true
                    if (matches.size < MAX_SEARCH_MATCHES) {
                        matches += SearchHit(relativeText, index + 1, boundedSnippet(line, found, query.length))
                    } else {
                        truncated = true
                    }
                    fromIndex = found + query.length.coerceAtLeast(1)
                }
            }
            if (fileMatched) matchedFiles++
        }
        return buildJsonObject {
            put("query", query)
            put("candidateFiles", candidates.size.coerceAtMost(MAX_SEARCH_FILES))
            put("scannedFiles", scannedFiles)
            put("matchedFiles", matchedFiles)
            put("totalMatches", totalMatches)
            put("returnedMatches", matches.size)
            put("truncated", truncated)
            putJsonArray("matches") {
                matches.forEach { match ->
                    add(buildJsonObject {
                        put("path", match.path)
                        put("line", match.line)
                        put("snippet", match.snippet)
                    })
                }
            }
        }
    }

    fun readFiles(paths: List<String>): JsonObject {
        require(paths.size in 1..MAX_READ_FILES) { "paths должен содержать от 1 до $MAX_READ_FILES файлов." }
        require(paths.distinct().size == paths.size) { "paths не должен содержать дубликаты." }
        val resolved = paths.map(::validateRelativePath).sortedBy(ValidatedPath::relative)
        var totalBytes = 0L
        val files = resolved.map { item ->
            val path = resolveExistingFile(item.relative)
            val size = Files.size(path)
            require(size <= MAX_READ_FILE_BYTES) { "Файл превышает допустимый размер чтения." }
            totalBytes += size
            require(totalBytes <= MAX_READ_TOTAL_BYTES) { "Суммарный размер файлов превышает допустимый предел." }
            val bytes = Files.readAllBytes(path)
            val content = decodeUtf8(bytes)
            ReadFile(item.relative, content, bytes.size, sha256(bytes))
        }
        return buildJsonObject {
            put("fileCount", files.size)
            put("totalBytes", totalBytes)
            putJsonArray("files") {
                files.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("content", file.content)
                        put("byteCount", file.byteCount)
                        put("sha256", file.sha256)
                    })
                }
            }
        }
    }

    fun writeFile(
        relativePath: String,
        content: String,
        mode: String,
        createOnly: Boolean,
        expectedSha256: String?,
    ): JsonObject {
        require(mode == "preview" || mode == "apply") { "mode должен быть preview или apply." }
        val validated = validateRelativePath(relativePath)
        require(isWritablePath(validated.relative)) { "Запись в этот путь запрещена политикой репозитория." }
        require(content.length <= MAX_WRITE_CONTENT_CHARS) { "content превышает допустимую длину." }
        require(content.all { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }) {
            "content содержит недопустимые управляющие символы."
        }
        val newBytes = content.toByteArray(StandardCharsets.UTF_8)
        require(newBytes.size <= MAX_WRITE_CONTENT_BYTES) { "content превышает допустимый размер UTF-8." }
        val target = resolveForWrite(validated.relative)
        val exists = Files.exists(target, NOFOLLOW_LINKS)
        require(createOnly == !exists) {
            if (exists) "Файл уже существует; createOnly-запись отклонена."
            else "Файл не существует; для создания требуется createOnly=true."
        }
        val previousBytes = if (exists) {
            require(Files.isRegularFile(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                "Целевой путь не является обычным файлом."
            }
            require(Files.size(target) <= MAX_READ_FILE_BYTES) { "Существующий файл превышает допустимый размер." }
            Files.readAllBytes(target).also(::decodeUtf8)
        } else {
            ByteArray(0)
        }
        val previousHash = previousBytes.takeIf { exists }?.let(::sha256)
        if (exists) {
            require(expectedSha256 != null && SHA256_REGEX.matches(expectedSha256)) {
                "Для изменения требуется корректный expectedSha256 из project_read_files."
            }
            require(previousHash == expectedSha256.lowercase()) { "Файл изменился: expectedSha256 устарел." }
        } else {
            require(expectedSha256 == null) { "При createOnly expectedSha256 должен отсутствовать." }
        }
        val newHash = sha256(newBytes)
        val noOp = exists && previousBytes.contentEquals(newBytes)
        val diff = if (noOp) {
            BoundedDiff("--- a/${validated.relative}\n+++ b/${validated.relative}\n", false)
        } else {
            unifiedDiff(validated.relative, previousBytes, newBytes, exists)
        }
        var applied = false
        if (mode == "apply" && !noOp) {
            if (exists) {
                require(sha256(Files.readAllBytes(target)) == previousHash) { "Файл изменился перед записью." }
            } else {
                require(!Files.exists(target, NOFOLLOW_LINKS)) { "Файл неожиданно появился перед записью." }
            }
            atomicWrite(target, newBytes, createOnly)
            applied = true
        }
        return buildJsonObject {
            put("path", validated.relative)
            put("mode", mode)
            put("applied", applied)
            put("created", !exists && applied)
            put("wouldCreate", !exists)
            put("noOp", noOp)
            previousHash?.let { put("previousSha256", it) }
            put("newSha256", newHash)
            put("byteCount", newBytes.size)
            put("diff", diff.text)
            put("diffTruncated", diff.truncated)
        }
    }

    private fun resolveExistingFile(relative: String): Path {
        val target = resolveChecked(relative)
        require(Files.exists(target, NOFOLLOW_LINKS)) { "Файл не найден." }
        require(Files.isRegularFile(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
            "Путь не является обычным файлом."
        }
        ensureNoSymlinkComponents(target)
        require(target.toRealPath().startsWith(root.toRealPath())) { "Путь выходит за пределы репозитория." }
        return target
    }

    private fun collectSearchCandidates(): List<String> {
        val collected = mutableListOf<String>()
        fun visit(directory: Path) {
            if (collected.size > MAX_SEARCH_FILES) return
            val children = Files.newDirectoryStream(directory).use { entries ->
                entries.toList().sortedBy { it.fileName.toString() }
            }
            for (child in children) {
                if (collected.size > MAX_SEARCH_FILES) return
                if (Files.isSymbolicLink(child)) continue
                val relative = root.relativize(child)
                if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                    val name = child.fileName.toString()
                    if (name in EXCLUDED_NAMES || name.startsWith(".llm-") ||
                        name == ".env" || name.startsWith(".env.")) continue
                    visit(child)
                } else if (Files.isRegularFile(child, NOFOLLOW_LINKS) && isAllowedRelativePath(relative)) {
                    collected += toPosix(relative)
                }
            }
        }
        visit(root)
        return collected.sorted()
    }

    private fun resolveForWrite(relative: String): Path {
        val target = resolveChecked(relative)
        val parent = requireNotNull(target.parent)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Родительский каталог не существует." }
        ensureNoSymlinkComponents(parent)
        require(parent.toRealPath().startsWith(root.toRealPath())) { "Путь выходит за пределы репозитория." }
        if (Files.exists(target, NOFOLLOW_LINKS)) ensureNoSymlinkComponents(target)
        return target
    }

    private fun resolveChecked(relative: String): Path {
        val validated = validateRelativePath(relative)
        val target = root.resolve(validated.relative).normalize()
        require(target.startsWith(root)) { "Путь выходит за пределы репозитория." }
        return target
    }

    private fun validateRelativePath(value: String): ValidatedPath {
        require(value.isNotBlank() && value.length <= MAX_PATH_LENGTH) { "path имеет недопустимую длину." }
        require('\\' !in value && !value.startsWith('/') && !WINDOWS_ABSOLUTE_PATH_REGEX.containsMatchIn(value) &&
            !Path.of(value).isAbsolute) {
            "path должен быть относительным POSIX-путём."
        }
        val segments = value.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) {
            "path содержит запрещённый компонент."
        }
        val normalized = segments.joinToString("/")
        require(isAllowedRelativePath(Path.of(normalized))) { "Тип или расположение файла запрещено политикой репозитория." }
        return ValidatedPath(normalized)
    }

    private fun ensureNoSymlinkComponents(target: Path) {
        var current = root
        root.relativize(target).forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Символьные ссылки в пути запрещены." }
        }
    }

    private fun isAllowedRelativePath(relative: Path): Boolean {
        val posix = toPosix(relative)
        if (posix.isBlank() || posix.length > MAX_PATH_LENGTH) return false
        val segments = posix.split('/')
        if (segments.any { segment ->
                segment.isBlank() || segment == "." || segment == ".." ||
                    segment in EXCLUDED_NAMES || segment.startsWith(".llm-") ||
                    segment == ".env" || segment.startsWith(".env.")
            }) return false
        if (segments.firstOrNull() in EXCLUDED_ROOTS) return false
        val fileName = segments.last()
        if (fileName in BLOCKED_FILES || SECRET_FILE_REGEX.containsMatchIn(fileName)) return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in ALLOWED_EXTENSIONS || fileName in ALLOWED_EXTENSIONLESS_FILES
    }

    private fun isWritablePath(relative: String): Boolean {
        if (relative == "AGENTS.md" || relative.startsWith(".github/")) return false
        if (relative in BLOCKED_WRITE_FILES) return false
        return relative == "README.md" || relative == "CHANGELOG.md" ||
            relative.startsWith("docs/") || relative.startsWith("src/") ||
            relative.startsWith("frontend/src/") || relative.startsWith("frontend/tests/")
    }

    private fun readUtf8(path: Path, maxBytes: Long): String {
        require(Files.size(path) <= maxBytes) { "Файл превышает допустимый размер." }
        return decodeUtf8(Files.readAllBytes(path))
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("Файл не является допустимым UTF-8 текстом.")
        }
        require('\u0000' !in text) { "Бинарные файлы запрещены." }
        return text
    }

    private fun atomicWrite(target: Path, bytes: ByteArray, createOnly: Boolean) {
        val temporary = Files.createTempFile(target.parent, ".project-write-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                if (createOnly) Files.move(temporary, target, ATOMIC_MOVE)
                else Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                if (createOnly) Files.move(temporary, target)
                else Files.move(temporary, target, REPLACE_EXISTING)
            } catch (_: FileAlreadyExistsException) {
                throw IllegalArgumentException("Файл неожиданно появился перед записью.")
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private data class ValidatedPath(val relative: String)
private data class SearchHit(val path: String, val line: Int, val snippet: String)
private data class ReadFile(val path: String, val content: String, val byteCount: Int, val sha256: String)
private data class BoundedDiff(val text: String, val truncated: Boolean)

private fun boundedSnippet(line: String, index: Int, queryLength: Int): String {
    val normalized = line.replace('\t', ' ').trim()
    if (normalized.length <= MAX_SNIPPET_LENGTH) return normalized
    val trimmedIndex = (index - (line.length - normalized.length)).coerceAtLeast(0)
    val start = (trimmedIndex - MAX_SNIPPET_LENGTH / 3).coerceIn(0, normalized.length - MAX_SNIPPET_LENGTH)
    val end = (start + MAX_SNIPPET_LENGTH).coerceAtMost(normalized.length)
    return (if (start > 0) "…" else "") + normalized.substring(start, end) +
        (if (end < normalized.length || queryLength > MAX_SNIPPET_LENGTH) "…" else "")
}

private fun unifiedDiff(path: String, previous: ByteArray, next: ByteArray, existed: Boolean): BoundedDiff {
    val oldText = if (existed) previous.toString(StandardCharsets.UTF_8) else ""
    val newText = next.toString(StandardCharsets.UTF_8)
    val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines().let { if (oldText.endsWith('\n')) it.dropLast(1) else it }
    val newLines = if (newText.isEmpty()) emptyList() else newText.lines().let { if (newText.endsWith('\n')) it.dropLast(1) else it }
    val raw = buildString {
        append("--- ").append(if (existed) "a/$path" else "/dev/null").append('\n')
        append("+++ b/").append(path).append('\n')
        append("@@ -1,").append(oldLines.size).append(" +1,").append(newLines.size).append(" @@\n")
        oldLines.forEach { append('-').append(it).append('\n') }
        newLines.forEach { append('+').append(it).append('\n') }
    }
    if (raw.length <= MAX_DIFF_LENGTH) return BoundedDiff(raw, false)
    return BoundedDiff(raw.take(MAX_DIFF_LENGTH - DIFF_TRUNCATION_MARKER.length) + DIFF_TRUNCATION_MARKER, true)
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun toPosix(path: Path): String = path.iterator().asSequence().joinToString("/") { it.toString() }

private const val MAX_QUERY_LENGTH = 200
private const val MAX_PATH_LENGTH = 240
private const val MAX_SEARCH_FILES = 2_000
private const val MAX_SEARCH_MATCHES = 50
private const val MAX_SEARCH_FILE_BYTES = 256L * 1024
private const val MAX_SEARCH_TOTAL_BYTES = 4L * 1024 * 1024
private const val MAX_SNIPPET_LENGTH = 240
private const val MAX_READ_FILES = 5
private const val MAX_READ_FILE_BYTES = 256L * 1024
private const val MAX_READ_TOTAL_BYTES = 768L * 1024
private const val MAX_WRITE_CONTENT_CHARS = 200_000
private const val MAX_WRITE_CONTENT_BYTES = 256 * 1024
private const val MAX_DIFF_LENGTH = 12_000
private const val DIFF_TRUNCATION_MARKER = "\n... diff truncated ...\n"
private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("^[A-Za-z]:/")
private val ALLOWED_EXTENSIONS = setOf("kt", "kts", "java", "ts", "tsx", "js", "jsx", "md", "txt", "json", "yaml", "yml", "css", "html")
private val ALLOWED_EXTENSIONLESS_FILES = setOf("README", "CHANGELOG")
private val EXCLUDED_ROOTS = setOf(".git", ".idea", ".gradle", "build", "dist", "node_modules", "video", ".llm-mcp-output")
private val EXCLUDED_NAMES = setOf(".git", ".idea", ".gradle", "build", "dist", "node_modules", "video", ".llm-mcp-output")
private val BLOCKED_FILES = setOf("AGENTS.md", ".env", ".env.example")
private val BLOCKED_WRITE_FILES = setOf(
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "frontend/package.json", "frontend/package-lock.json",
)
private val SECRET_FILE_REGEX = Regex(
    "(?i)((^|[-_.])(secret|credentials?|private[-_.]?key|api[-_.]?key|tokens?|keystore)([-_.]|$)|\\.pem$|\\.p12$)",
)
