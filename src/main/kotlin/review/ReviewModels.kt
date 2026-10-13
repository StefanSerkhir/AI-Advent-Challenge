package org.example.review

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

const val AI_REVIEW_COMMENT_MARKER = "<!-- ai-code-review:v1 -->"
const val DEFAULT_AI_REVIEW_MODEL = "gpt-5.6-sol"
const val DEFAULT_REVIEW_DIFF_LIMIT = 120_000
const val DEFAULT_REVIEW_MAX_TOKENS = 8_000

@Serializable
data class PullRequestMetadata(
    val repository: String,
    val number: Int,
    val title: String,
    val body: String,
    val baseSha: String,
    val headSha: String,
    val baseRepository: String = repository,
    val headRepository: String = repository,
    val draft: Boolean = false,
)

enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, COPIED, TYPE_CHANGED, UNKNOWN }

data class ChangedFile(
    val path: String,
    val kind: ChangeKind,
    val previousPath: String? = null,
    val binary: Boolean = false,
    val patch: String = "",
    val changedLines: Set<Int> = emptySet(),
)

data class PullRequestDiff(
    val files: List<ChangedFile>,
) {
    val unifiedDiff: String get() = files.joinToString("\n") { it.patch }.trimEnd()
    val characterCount: Int get() = unifiedDiff.length
}

@Serializable
data class SkippedFile(
    val path: String,
    val reason: String,
)

data class BoundedReviewDiff(
    val fullCharacterCount: Int,
    val promptDiff: String,
    val reviewedFiles: List<String>,
    val skippedFiles: List<SkippedFile>,
    val changedLines: Map<String, Set<Int>>,
) {
    val partial: Boolean get() = skippedFiles.isNotEmpty()
}

@Serializable
enum class RagDomain { DOCUMENTATION, CODE }

@Serializable
data class ReviewRagSource(
    val id: String,
    val domain: RagDomain,
    val source: String,
    val section: String,
    val chunkId: String,
    val rank: Int,
    val score: Double,
    @Transient val text: String = "",
)

enum class ReviewSeverity { P0, P1, P2, P3 }

enum class ReviewCategory {
    BUG,
    SECURITY,
    ARCHITECTURE,
    CONCURRENCY,
    STATE_PERSISTENCE,
    PERFORMANCE,
    TESTING,
    RECOMMENDATION,
}

enum class ReviewVerdict { PASS, COMMENT, REQUEST_CHANGES }

@Serializable
data class ReviewEvidence(
    @SerialName("source_id") val sourceId: String,
    val quote: String,
)

@Serializable
data class ReviewFinding(
    @SerialName("local_id") val localId: String,
    val severity: ReviewSeverity,
    val category: ReviewCategory,
    val confidence: Double,
    val file: String,
    val line: Int?,
    val title: String,
    val problem: String,
    val impact: String,
    val recommendation: String,
    val evidence: List<ReviewEvidence>,
)

@Serializable
data class ReviewResult(
    val summary: String,
    val verdict: ReviewVerdict,
    val partial: Boolean,
    val reviewedFiles: List<String>,
    val skippedFiles: List<SkippedFile>,
    val findings: List<ReviewFinding>,
    val usedRagSources: List<ReviewRagSource>,
)

@Serializable
data class ReviewDiagnostics(
    val model: String,
    val analysedCommit: String,
    val changedFileCount: Int,
    val fullDiffCharacters: Int,
    val promptDiffCharacters: Int,
    val documentationSourceCount: Int,
    val codeSourceCount: Int,
    val partial: Boolean,
)

@Serializable
data class ReviewArtifact(
    val result: ReviewResult,
    val diagnostics: ReviewDiagnostics,
    val markdown: String,
)

enum class ReviewRunEligibility { ELIGIBLE, DRAFT, FORK, MISSING_OPENAI_KEY, MISSING_GITHUB_TOKEN }

fun reviewRunEligibility(
    metadata: PullRequestMetadata,
    hasOpenAiKey: Boolean,
    hasGitHubToken: Boolean,
    publish: Boolean,
): ReviewRunEligibility = when {
    metadata.draft -> ReviewRunEligibility.DRAFT
    metadata.headRepository != metadata.baseRepository -> ReviewRunEligibility.FORK
    !hasOpenAiKey -> ReviewRunEligibility.MISSING_OPENAI_KEY
    publish && !hasGitHubToken -> ReviewRunEligibility.MISSING_GITHUB_TOKEN
    else -> ReviewRunEligibility.ELIGIBLE
}
