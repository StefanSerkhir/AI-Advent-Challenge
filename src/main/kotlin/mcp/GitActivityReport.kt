package org.example.mcp

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime

const val GIT_ACTIVITY_REPORT_TOOL = "git_activity_report"
const val DEFAULT_GIT_ACTIVITY_SINCE_HOURS = 24
const val DEFAULT_GIT_ACTIVITY_MAX_COMMITS = 20
const val DEFAULT_GIT_ACTIVITY_MAX_FILES = 50
const val MAX_GIT_ACTIVITY_SINCE_HOURS = 168
const val MAX_GIT_ACTIVITY_COMMITS = 50
const val MAX_GIT_ACTIVITY_FILES = 100

internal data class GitActivityCommit(
    val sourceId: String,
    val sha: String,
    val authoredAt: String,
    val subject: String,
)

internal data class GitActivityFile(
    val sourceId: String,
    val path: String,
    val status: String,
    val scope: String,
    val insertions: Int?,
    val deletions: Int?,
)

internal data class GitWorkingTree(
    val dirty: Boolean,
    val stagedCount: Int,
    val unstagedCount: Int,
    val untrackedCount: Int,
)

internal data class GitActivityReport(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val since: String,
    val branch: String?,
    val detached: Boolean,
    val head: String?,
    val commits: List<GitActivityCommit>,
    val files: List<GitActivityFile>,
    val workingTree: GitWorkingTree,
    val truncated: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("generatedAt", generatedAt)
        put("since", since)
        if (branch == null) put("branch", JsonNull) else put("branch", branch)
        put("detached", detached)
        if (head == null) put("head", JsonNull) else put("head", head)
        put("commits", buildJsonArray {
            commits.forEach { commit ->
                add(buildJsonObject {
                    put("sourceId", commit.sourceId)
                    put("sha", commit.sha)
                    put("authoredAt", commit.authoredAt)
                    put("subject", commit.subject)
                })
            }
        })
        put("files", buildJsonArray {
            files.forEach { file ->
                add(buildJsonObject {
                    put("sourceId", file.sourceId)
                    put("path", file.path)
                    put("status", file.status)
                    put("scope", file.scope)
                    if (file.insertions == null) put("insertions", JsonNull) else put("insertions", file.insertions)
                    if (file.deletions == null) put("deletions", JsonNull) else put("deletions", file.deletions)
                })
            }
        })
        put("workingTree", buildJsonObject {
            put("dirty", workingTree.dirty)
            put("stagedCount", workingTree.stagedCount)
            put("unstagedCount", workingTree.unstagedCount)
            put("untrackedCount", workingTree.untrackedCount)
        })
        put("truncated", truncated)
    }
}

internal fun gitActivityReport(
    repositoryRoot: Path,
    sinceHours: Int = DEFAULT_GIT_ACTIVITY_SINCE_HOURS,
    maxCommits: Int = DEFAULT_GIT_ACTIVITY_MAX_COMMITS,
    maxFiles: Int = DEFAULT_GIT_ACTIVITY_MAX_FILES,
    clock: Clock = Clock.systemUTC(),
    runner: GitCommandRunner = ProcessBuilderGitCommandRunner,
): JsonObject {
    require(sinceHours in 1..MAX_GIT_ACTIVITY_SINCE_HOURS) {
        "sinceHours должен быть целым числом от 1 до $MAX_GIT_ACTIVITY_SINCE_HOURS."
    }
    require(maxCommits in 1..MAX_GIT_ACTIVITY_COMMITS) {
        "maxCommits должен быть целым числом от 1 до $MAX_GIT_ACTIVITY_COMMITS."
    }
    require(maxFiles in 1..MAX_GIT_ACTIVITY_FILES) {
        "maxFiles должен быть целым числом от 1 до $MAX_GIT_ACTIVITY_FILES."
    }
    require(Files.isDirectory(repositoryRoot, NOFOLLOW_LINKS)) { "Настроенный корень репозитория недоступен." }
    val inside = runner.run(repositoryRoot, listOf("rev-parse", "--is-inside-work-tree"))
    require(inside.exitCode == 0 && inside.output.trim() == "true") { "Настроенный корень не является Git-репозиторием." }

    val generatedAt = clock.instant()
    val since = generatedAt.minusSeconds(sinceHours.toLong() * 3_600L)
    val branchResult = runner.run(repositoryRoot, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
    val branch = branchResult.takeIf { it.exitCode == 0 }
        ?.output?.trim()?.takeIf(::isSafeGitMetadataValue)
    val headResult = runner.run(repositoryRoot, listOf("rev-parse", "--short=12", "--verify", "HEAD"))
    val head = headResult.takeIf { it.exitCode == 0 }
        ?.output?.trim()?.takeIf { GIT_ACTIVITY_SHA.matches(it) }
    val detached = head != null && branchResult.exitCode != 0

    val commitResult = if (head == null) {
        GitCommandResult(0, "")
    } else {
        runner.run(repositoryRoot, listOf(
            "log", "--no-color", "--since=${since}", "--max-count=${maxCommits + 1}",
            "--date=iso-strict", "--format=%H%x1f%aI%x1f%s%x1e",
        ))
    }
    require(commitResult.exitCode == 0) { "Не удалось получить историю Git." }
    val parsedCommits = parseCommitRecords(commitResult.output)
    val commitsTruncated = parsedCommits.size > maxCommits || commitResult.truncated
    val commits = parsedCommits.take(maxCommits).mapIndexed { index, commit ->
        commit.copy(sourceId = "C${index + 1}")
    }

    val commitArguments = listOf(
        "log", "--no-color", "--since=${since}", "--max-count=$maxCommits", "--format=", "--no-renames",
    )
    val committedStatusesResult = if (head == null) GitCommandResult(0, "") else
        runner.run(repositoryRoot, commitArguments + listOf("--name-status", "-z"))
    val committedNumstatResult = if (head == null) GitCommandResult(0, "") else
        runner.run(repositoryRoot, commitArguments + listOf("--numstat", "-z"))
    require(committedStatusesResult.exitCode == 0 && committedNumstatResult.exitCode == 0) {
        "Не удалось получить список изменённых Git-файлов."
    }
    val committedStatuses = parseNameStatus(committedStatusesResult.output)
    val committedStats = parseNumstat(committedNumstatResult.output)
    val committedFiles = (committedStatuses.keys + committedStats.keys)
        .filter(::isSafeGitActivityPath)
        .distinct()
        .sorted()
        .map { path ->
            val stats = committedStats[path]
            GitActivityFile(
                sourceId = "",
                path = path,
                status = summarizeCommittedStatus(committedStatuses[path].orEmpty()),
                scope = "committed",
                insertions = stats?.insertions,
                deletions = stats?.deletions,
            )
        }

    val workingStatusResult = runner.run(repositoryRoot, listOf(
        "-c", "status.renames=false", "status", "--porcelain=v1", "-z", "--untracked-files=all",
    ))
    require(workingStatusResult.exitCode == 0) { "Не удалось получить состояние рабочей директории." }
    val workingEntries = parseWorkingStatus(workingStatusResult.output)
    val unstagedStatsResult = runner.run(repositoryRoot, listOf("diff", "--no-renames", "--numstat", "-z"))
    val stagedStatsResult = runner.run(repositoryRoot, listOf("diff", "--cached", "--no-renames", "--numstat", "-z"))
    require(unstagedStatsResult.exitCode == 0 && stagedStatsResult.exitCode == 0) {
        "Не удалось получить статистику рабочей директории."
    }
    val workingStats = mergeStats(parseNumstat(unstagedStatsResult.output), parseNumstat(stagedStatsResult.output))
    val workingFiles = workingEntries
        .filter { isSafeGitActivityPath(it.path) }
        .sortedBy(WorkingEntry::path)
        .map { entry ->
            val stats = workingStats[entry.path]
            GitActivityFile(
                sourceId = "",
                path = entry.path,
                status = entry.status,
                scope = "working_tree",
                insertions = stats?.insertions,
                deletions = stats?.deletions,
            )
        }

    val allFiles = committedFiles + workingFiles
    val filesTruncated = allFiles.size > maxFiles || listOf(
        committedStatusesResult, committedNumstatResult, workingStatusResult, unstagedStatsResult, stagedStatsResult,
    ).any(GitCommandResult::truncated)
    val files = allFiles.take(maxFiles).mapIndexed { index, file -> file.copy(sourceId = "F${index + 1}") }
    val workingTree = GitWorkingTree(
        dirty = workingEntries.isNotEmpty(),
        stagedCount = workingEntries.count(WorkingEntry::staged),
        unstagedCount = workingEntries.count(WorkingEntry::unstaged),
        untrackedCount = workingEntries.count(WorkingEntry::untracked),
    )

    return GitActivityReport(
        generatedAt = generatedAt.toString(),
        since = since.toString(),
        branch = branch,
        detached = detached,
        head = head,
        commits = commits,
        files = files,
        workingTree = workingTree,
        truncated = commitsTruncated || filesTruncated,
    ).toJson()
}

private data class MutableFileStats(val insertions: Int?, val deletions: Int?)
private data class WorkingEntry(
    val path: String,
    val status: String,
    val staged: Boolean,
    val unstaged: Boolean,
    val untracked: Boolean,
)

private fun parseCommitRecords(output: String): List<GitActivityCommit> = output
    .split('\u001e')
    .map { it.trim('\n', '\r') }
    .filter(String::isNotBlank)
    .mapNotNull { record ->
        val parts = record.split('\u001f', limit = 3)
        if (parts.size != 3 || !GIT_ACTIVITY_FULL_SHA.matches(parts[0])) return@mapNotNull null
        val authoredAt = runCatching { OffsetDateTime.parse(parts[1]).toInstant().toString() }.getOrNull()
            ?: return@mapNotNull null
        GitActivityCommit(
            sourceId = "",
            sha = parts[0].take(12),
            authoredAt = authoredAt,
            subject = sanitizeGitMetadata(parts[2], MAX_COMMIT_SUBJECT_LENGTH).ifBlank { "(без темы)" },
        )
    }

private fun parseNameStatus(output: String): Map<String, List<String>> {
    val values = output.split('\u0000').filter(String::isNotEmpty)
    val statuses = linkedMapOf<String, MutableList<String>>()
    var index = 0
    while (index + 1 < values.size) {
        val status = values[index].trim()
        val path = values[index + 1]
        if (status.matches(Regex("^[ADMTCUXB]$"))) statuses.getOrPut(path) { mutableListOf() } += status
        index += 2
    }
    return statuses
}

private fun parseNumstat(output: String): Map<String, MutableFileStats> {
    val result = linkedMapOf<String, MutableFileStats>()
    output.split('\u0000').filter(String::isNotEmpty).forEach { record ->
        val parts = record.split('\t', limit = 3)
        if (parts.size != 3) return@forEach
        val path = parts[2]
        val insertions = parts[0].toIntOrNull()
        val deletions = parts[1].toIntOrNull()
        val previous = result[path]
        result[path] = MutableFileStats(
            insertions = boundedSum(previous?.insertions, insertions),
            deletions = boundedSum(previous?.deletions, deletions),
        )
    }
    return result
}

private fun mergeStats(
    first: Map<String, MutableFileStats>,
    second: Map<String, MutableFileStats>,
): Map<String, MutableFileStats> = (first.keys + second.keys).associateWith { path ->
    MutableFileStats(
        boundedSum(first[path]?.insertions, second[path]?.insertions),
        boundedSum(first[path]?.deletions, second[path]?.deletions),
    )
}

private fun boundedSum(first: Int?, second: Int?): Int? = when {
    first == null && second == null -> null
    first == null -> second
    second == null -> first
    else -> (first.toLong() + second.toLong()).coerceAtMost(MAX_FILE_CHANGE_COUNT.toLong()).toInt()
}

private fun parseWorkingStatus(output: String): List<WorkingEntry> = output
    .split('\u0000')
    .filter(String::isNotEmpty)
    .mapNotNull { record ->
        if (record.length < 4 || record[2] != ' ') return@mapNotNull null
        val x = record[0]
        val y = record[1]
        val path = record.substring(3)
        val untracked = x == '?' && y == '?'
        val staged = !untracked && x != ' '
        val unstaged = !untracked && y != ' '
        WorkingEntry(
            path = path,
            status = when {
                untracked -> "untracked"
                staged && unstaged -> "staged+unstaged"
                staged -> "staged"
                else -> "unstaged"
            },
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
        )
    }

private fun summarizeCommittedStatus(statuses: List<String>): String = when (statuses.distinct()) {
    listOf("A") -> "added"
    listOf("M") -> "modified"
    listOf("D") -> "deleted"
    listOf("T") -> "type_changed"
    emptyList<String>() -> "changed"
    else -> "changed"
}

private fun isSafeGitMetadataValue(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_BRANCH_NAME_LENGTH &&
        !value.startsWith('/') && '\\' !in value && '`' !in value && value.none(Char::isISOControl) &&
        !GIT_ACTIVITY_SENSITIVE_VALUE_REGEX.containsMatchIn(value)

internal fun isSafeGitActivityPath(value: String): Boolean {
    if (value.isBlank() || value.length > MAX_GIT_ACTIVITY_PATH_LENGTH || value.startsWith('/') || '\\' in value || '`' in value) {
        return false
    }
    val segments = value.split('/')
    if (segments.any { segment ->
            segment.isBlank() || segment == "." || segment == ".." || segment.any(Char::isISOControl) ||
                segment in GIT_ACTIVITY_EXCLUDED_NAMES || segment.startsWith(".llm-") ||
                segment == ".env" || segment.startsWith(".env.")
        }) return false
    if (segments.firstOrNull() in GIT_ACTIVITY_EXCLUDED_ROOTS) return false
    return segments.none(GIT_ACTIVITY_SECRET_FILE_REGEX::containsMatchIn) &&
        !GIT_ACTIVITY_SENSITIVE_VALUE_REGEX.containsMatchIn(value)
}

private fun sanitizeGitMetadata(value: String, maxLength: Int): String = value
    .map { character -> if (character.isISOControl() || character == '`') ' ' else character }
    .joinToString("")
    .replace(Regex("\\s+"), " ")
    .trim()
    .replace(GIT_ACTIVITY_SENSITIVE_VALUE_REGEX, "[redacted]")
    .take(maxLength)

private const val MAX_COMMIT_SUBJECT_LENGTH = 300
private const val MAX_GIT_ACTIVITY_PATH_LENGTH = 240
private const val MAX_FILE_CHANGE_COUNT = 1_000_000
private const val MAX_BRANCH_NAME_LENGTH = 255
private val GIT_ACTIVITY_SHA = Regex("^[0-9a-fA-F]{7,64}$")
private val GIT_ACTIVITY_FULL_SHA = Regex("^[0-9a-fA-F]{40,64}$")
private val GIT_ACTIVITY_EXCLUDED_ROOTS = setOf(
    ".git", ".idea", ".gradle", "build", "dist", "node_modules", "video", ".llm-mcp-output",
)
private val GIT_ACTIVITY_EXCLUDED_NAMES = GIT_ACTIVITY_EXCLUDED_ROOTS
private val GIT_ACTIVITY_SECRET_FILE_REGEX = Regex(
    "(?i)((^|[-_.])(secret|credentials?|private[-_.]?key|api[-_.]?key|tokens?|keystore)([-_.]|$)|\\.pem$|\\.p12$)",
)
private val GIT_ACTIVITY_SENSITIVE_VALUE_REGEX = Regex(
    "(?i)(bearer\\s+[A-Za-z0-9._~-]{8,}|sk-(?:proj-)?[A-Za-z0-9_-]{8,}|" +
        "gh[pousr]_[A-Za-z0-9]{12,}|xox[baprs]-[A-Za-z0-9-]{8,}|AKIA[0-9A-Z]{12,}|AIza[0-9A-Za-z_-]{12,})",
)
