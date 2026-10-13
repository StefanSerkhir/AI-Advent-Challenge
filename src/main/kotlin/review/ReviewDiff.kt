package org.example.review

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private val commitShaPattern = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
private val hunkHeaderPattern = Regex("^@@ -(?:\\d+)(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@")

fun validateCommitSha(value: String, name: String): String = value.lowercase().also {
    require(commitShaPattern.matches(it)) { "$name должен быть полным 40- или 64-символьным hex commit SHA" }
}

fun validateRepositoryPath(value: String): String {
    require(value.isNotBlank()) { "Путь изменённого файла не может быть пустым" }
    require(!value.startsWith('/') && !Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value)) {
        "Путь изменённого файла должен быть относительным: $value"
    }
    require('\\' !in value && value.split('/').none { it == ".." || it.isEmpty() }) {
        "Путь изменённого файла содержит недопустимые сегменты: $value"
    }
    require(value.none(Char::isISOControl)) { "Путь изменённого файла содержит управляющие символы" }
    return value
}

class GitCommandException(message: String) : IllegalStateException(message)

fun interface ProcessExecutor {
    fun execute(root: Path, arguments: List<String>): ByteArray
}

class SafeProcessExecutor : ProcessExecutor {
    override fun execute(root: Path, arguments: List<String>): ByteArray {
        require(arguments.isNotEmpty())
        val process = ProcessBuilder(arguments)
            .directory(root.toFile())
            .redirectErrorStream(false)
            .apply {
                environment()["GIT_PAGER"] = "cat"
                environment()["LC_ALL"] = "C"
            }
            .start()
        val output = process.inputStream.readAllBytes()
        val error = process.errorStream.readAllBytes()
        val exit = process.waitFor()
        if (exit != 0) {
            val reason = error.toString(StandardCharsets.UTF_8)
                .filter { it == '\n' || it == '\t' || !it.isISOControl() }
                .take(1_000)
                .ifBlank { "exit code $exit" }
            throw GitCommandException("Git command failed: $reason")
        }
        return output
    }
}

class GitPullRequestDiffCollector(
    private val executor: ProcessExecutor = SafeProcessExecutor(),
) {
    fun collect(root: Path, baseSha: String, headSha: String): PullRequestDiff {
        val absoluteRoot = root.toAbsolutePath().normalize()
        require(Files.isDirectory(absoluteRoot)) { "Repository root не является каталогом: $root" }
        val base = validateCommitSha(baseSha, "base SHA")
        val head = validateCommitSha(headSha, "head SHA")
        verifyCommit(absoluteRoot, base)
        verifyCommit(absoluteRoot, head)
        verifyTrustedBaseCheckout(absoluteRoot, base)

        val statusBytes = executor.execute(
            absoluteRoot,
            listOf("git", "diff", "--name-status", "-z", "--find-renames", "--find-copies", base, head, "--"),
        )
        val statuses = parseNameStatus(statusBytes)
        val files = statuses.map { status ->
            val pathspecs = listOfNotNull(status.previousPath, status.path).distinct()
            val patch = executor.execute(
                absoluteRoot,
                listOf(
                    "git", "diff", "--no-color", "--no-ext-diff", "--no-textconv", "--find-renames", "--find-copies",
                    "--full-index", "--unified=80", base, head, "--",
                ) + pathspecs,
            ).let { decodeGitUtf8(it, "unified diff") }.replace("\r\n", "\n").replace('\r', '\n')
            val binary = patch.lineSequence().any {
                it.startsWith("Binary files ") || it == "GIT binary patch" || it.startsWith("literal ")
            }
            ChangedFile(
                path = status.path,
                previousPath = status.previousPath,
                kind = status.kind,
                binary = binary,
                patch = patch,
                changedLines = if (binary) emptySet() else parseChangedLines(patch),
            )
        }
        require(files.map(ChangedFile::path).distinct().size == files.size) { "Git вернул повторяющиеся changed paths" }
        return PullRequestDiff(files)
    }

    private fun verifyCommit(root: Path, sha: String) {
        executor.execute(root, listOf("git", "cat-file", "-e", "$sha^{commit}"))
    }

    private fun verifyTrustedBaseCheckout(root: Path, baseSha: String) {
        val checkedOut = decodeGitUtf8(executor.execute(root, listOf("git", "rev-parse", "HEAD")), "checked-out SHA")
            .trim().lowercase()
        require(checkedOut == baseSha) {
            "Repository checkout must be the exact trusted base SHA; expected $baseSha, got $checkedOut"
        }
        val status = decodeGitUtf8(
            executor.execute(root, listOf("git", "status", "--porcelain=v1", "--untracked-files=all")),
            "working-tree status",
        )
        require(status.isBlank()) { "Trusted base checkout must be clean before indexing and review" }
    }
}

data class NameStatus(val kind: ChangeKind, val path: String, val previousPath: String? = null)

fun parseNameStatus(bytes: ByteArray): List<NameStatus> {
    if (bytes.isEmpty()) return emptyList()
    val fields = decodeGitUtf8(bytes, "changed-file list").split('\u0000').dropLastWhile(String::isEmpty)
    val result = mutableListOf<NameStatus>()
    var index = 0
    while (index < fields.size) {
        val status = fields[index++]
        require(status.isNotBlank()) { "Git name-status содержит пустой статус" }
        val code = status.first()
        val kind = when (code) {
            'A' -> ChangeKind.ADDED
            'M' -> ChangeKind.MODIFIED
            'D' -> ChangeKind.DELETED
            'R' -> ChangeKind.RENAMED
            'C' -> ChangeKind.COPIED
            'T' -> ChangeKind.TYPE_CHANGED
            else -> ChangeKind.UNKNOWN
        }
        if (kind == ChangeKind.RENAMED || kind == ChangeKind.COPIED) {
            val previous = validateRepositoryPath(fields.getOrNull(index++)
                ?: throw IllegalArgumentException("Git name-status не содержит исходный путь rename/copy"))
            val path = validateRepositoryPath(fields.getOrNull(index++)
                ?: throw IllegalArgumentException("Git name-status не содержит новый путь rename/copy"))
            result += NameStatus(kind, path, previous)
        } else {
            val path = validateRepositoryPath(fields.getOrNull(index++)
                ?: throw IllegalArgumentException("Git name-status не содержит путь"))
            result += NameStatus(kind, path)
        }
    }
    return result
}

private fun decodeGitUtf8(bytes: ByteArray, label: String): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    throw IllegalArgumentException("Git $label is not valid UTF-8")
}

fun parseChangedLines(patch: String): Set<Int> {
    val changed = linkedSetOf<Int>()
    var inHunk = false
    var newLine = 0
    patch.lineSequence().forEach { line ->
        val header = hunkHeaderPattern.find(line)
        if (header != null) {
            inHunk = true
            newLine = header.groupValues[1].toInt()
            return@forEach
        }
        if (!inHunk) return@forEach
        when {
            line.startsWith("diff --git ") -> inHunk = false
            line.startsWith("+") && !line.startsWith("+++") -> changed += newLine++
            line.startsWith("-") && !line.startsWith("---") -> Unit
            line.startsWith(" ") -> newLine++
            line == "\\ No newline at end of file" -> Unit
            else -> inHunk = false
        }
    }
    return changed
}

class ReviewDiffLimiter(private val maximumCharacters: Int = DEFAULT_REVIEW_DIFF_LIMIT) {
    init {
        require(maximumCharacters > 0) { "Diff limit должен быть положительным" }
    }

    fun limit(diff: PullRequestDiff): BoundedReviewDiff {
        val included = mutableListOf<ChangedFile>()
        val skipped = mutableListOf<SkippedFile>()
        var used = 0
        diff.files.forEach { file ->
            val reason = when {
                file.binary -> "binary file: content was not sent to the model"
                file.patch.isBlank() -> "no textual unified diff was available"
                file.patch.length > maximumCharacters - used ->
                    "diff size limit ($maximumCharacters characters) would be exceeded"
                else -> null
            }
            if (reason == null) {
                included += file
                used += file.patch.length + 1
            } else {
                skipped += SkippedFile(file.path, reason)
            }
        }
        return BoundedReviewDiff(
            fullCharacterCount = diff.characterCount,
            promptDiff = included.joinToString("\n") { it.patch }.trimEnd(),
            reviewedFiles = included.map(ChangedFile::path),
            skippedFiles = skipped,
            changedLines = included.associate { it.path to it.changedLines },
        )
    }
}
