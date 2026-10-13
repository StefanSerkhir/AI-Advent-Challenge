package org.example.review

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

private const val MAX_REVIEW_RESULT_CHARACTERS = 50_000

class ReviewValidationException(message: String) : IllegalArgumentException(message)

class SecretRedactor(knownSecrets: List<String> = emptyList()) {
    private val secrets = knownSecrets.filter { it.length >= 6 }.distinct().sortedByDescending(String::length)
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val openAiKey = Regex("sk-[A-Za-z0-9_-]{8,}")

    fun redact(value: String): String {
        var result = value
        secrets.forEach { result = result.replace(it, "<redacted>") }
        return result.replace(bearer, "Bearer <redacted>").replace(openAiKey, "<redacted>")
    }

    fun containsSecret(value: String): Boolean = redact(value) != value
}

class ReviewOutputValidator(
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val redactor: SecretRedactor = SecretRedactor(),
) {
    fun decodeAndValidate(
        raw: String,
        boundedDiff: BoundedReviewDiff,
        allChangedPaths: Set<String>,
        ragSources: List<ReviewRagSource>,
    ): ReviewResult {
        val decoded = try {
            json.decodeFromString<ModelReviewOutput>(raw)
        } catch (_: SerializationException) {
            throw ReviewValidationException("Structured review is not valid JSON for the required schema")
        }
        validateExactCoverage(decoded, boundedDiff)
        val sourcesById = ragSources.associateBy(ReviewRagSource::id)
        requireReview(sourcesById.size == ragSources.size, "RAG source IDs are not unique")
        requireReview(decoded.summary.isNotBlank(), "Review summary is empty")
        requireReview(decoded.findings.map(ReviewFinding::localId).distinct().size == decoded.findings.size, "Finding IDs are duplicated")
        decoded.findings.forEachIndexed { index, finding ->
            requireReview(finding.localId == "F-${(index + 1).toString().padStart(3, '0')}", "Finding IDs must be sequential F-001, F-002, ...")
            requireReview(finding.confidence.isFinite() && finding.confidence in 0.0..1.0, "Finding ${finding.localId} has invalid confidence")
            requireReview(finding.file in allChangedPaths, "Finding ${finding.localId} references a file outside the pull request")
            requireReview(finding.file in boundedDiff.reviewedFiles, "Finding ${finding.localId} references a skipped file")
            finding.line?.let { line ->
                requireReview(line in boundedDiff.changedLines[finding.file].orEmpty(), "Finding ${finding.localId} line is not a changed line")
            }
            listOf(finding.title, finding.problem, finding.impact, finding.recommendation).forEach { text ->
                requireReview(text.isNotBlank(), "Finding ${finding.localId} contains an empty required field")
            }
            requireReview(validateRepositoryPath(finding.file) == finding.file, "Finding ${finding.localId} contains an invalid path")
            if (finding.category == ReviewCategory.ARCHITECTURE) {
                requireReview(finding.evidence.isNotEmpty(), "Architecture finding ${finding.localId} requires RAG evidence")
            }
            finding.evidence.forEach { evidence ->
                val source = sourcesById[evidence.sourceId]
                    ?: throw ReviewValidationException("Finding ${finding.localId} references unknown RAG source ${evidence.sourceId}")
                if (evidence.quote.isNotBlank()) {
                    requireReview(evidence.quote.length <= 600, "RAG quote is longer than 600 characters")
                    requireReview(normalize(evidence.quote) in normalize(source.text), "RAG quote for ${evidence.sourceId} is not exact")
                }
            }
        }
        val evidenceIds = decoded.findings.flatMap { finding -> finding.evidence.map(ReviewEvidence::sourceId) }.toSet()
        requireReview(decoded.usedRagSourceIds.distinct().size == decoded.usedRagSourceIds.size, "usedRagSourceIds contains duplicates")
        requireReview(decoded.usedRagSourceIds.toSet() == evidenceIds, "usedRagSourceIds must equal the evidence IDs used by findings")
        val result = ReviewResult(
            summary = decoded.summary.trim(),
            verdict = decoded.verdict,
            partial = decoded.partial,
            reviewedFiles = decoded.reviewedFiles,
            skippedFiles = decoded.skippedFiles,
            findings = decoded.findings,
            usedRagSources = decoded.usedRagSourceIds.map { sourcesById.getValue(it) },
        )
        val serialized = json.encodeToString(result)
        requireReview(serialized.length <= MAX_REVIEW_RESULT_CHARACTERS, "Structured review exceeds the local size limit")
        requireReview(!redactor.containsSecret(serialized), "Structured review contains a secret or bearer token")
        requireReview(!containsLocalAbsolutePath(serialized), "Structured review contains an absolute local path")
        return result
    }

    private fun validateExactCoverage(output: ModelReviewOutput, boundedDiff: BoundedReviewDiff) {
        requireReview(output.partial == boundedDiff.partial, "partial flag does not match local diff coverage")
        requireReview(output.reviewedFiles == boundedDiff.reviewedFiles, "reviewedFiles does not match local diff coverage")
        requireReview(output.skippedFiles == boundedDiff.skippedFiles, "skippedFiles does not match local diff coverage")
    }

    private fun requireReview(condition: Boolean, message: String) {
        if (!condition) throw ReviewValidationException(message)
    }

    private fun normalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun containsLocalAbsolutePath(value: String): Boolean = listOf(
        Regex("(?:^|[\\s'\"`(])/(?:Users|home|tmp|private/tmp|var/folders)/"),
        Regex("(?:^|[\\s'\"`(])[A-Za-z]:\\\\"),
        Regex("file://", RegexOption.IGNORE_CASE),
    ).any { it.containsMatchIn(value) }
}

class ReviewMarkdownRenderer(private val redactor: SecretRedactor = SecretRedactor()) {
    fun render(result: ReviewResult, diagnostics: ReviewDiagnostics): String = buildString {
        appendLine(AI_REVIEW_COMMENT_MARKER)
        appendLine("# AI code review")
        appendLine()
        appendLine("Analysed commit: ${inlineCode(diagnostics.analysedCommit)}")
        appendLine()
        appendLine(safe(result.summary))
        appendLine()
        appendLine("**Verdict:** ${inlineCode(result.verdict.name)} · **Coverage:** ${if (result.partial) "partial" else "full"}")
        appendLine()
        appendLine("## Findings")
        appendLine()
        if (result.findings.isEmpty()) {
            appendLine("No demonstrable issues were found in the analysed diff. This does not guarantee that the code is correct.")
        } else {
            result.findings.sortedWith(compareBy<ReviewFinding>({ it.severity.ordinal }, { it.file }, { it.line ?: Int.MAX_VALUE }, { it.localId }))
                .forEach { finding ->
                    val location = if (finding.line == null) {
                        "${inlineCode(finding.file)} (file-level)"
                    } else {
                        inlineCode("${finding.file}:${finding.line}")
                    }
                    appendLine("### ${safe(finding.title)} ${inlineCode(finding.localId)}")
                    appendLine()
                    appendLine(
                        "${inlineCode(finding.severity.name)} · ${inlineCode(finding.category.name)} · " +
                            "confidence ${inlineCode("%.2f".format(java.util.Locale.ROOT, finding.confidence))} · $location",
                    )
                    appendLine()
                    appendLine("**Problem:** ${safe(finding.problem)}")
                    appendLine()
                    appendLine("**Impact:** ${safe(finding.impact)}")
                    appendLine()
                    appendLine("**Recommendation:** ${safe(finding.recommendation)}")
                    if (finding.evidence.isNotEmpty()) {
                        appendLine()
                        appendLine("**RAG evidence:**")
                        finding.evidence.forEach { evidence ->
                            val quote = evidence.quote.takeIf(String::isNotBlank)?.let { ": “${safe(it)}”" }.orEmpty()
                            appendLine("- ${inlineCode("[${evidence.sourceId}]")}$quote")
                        }
                    }
                    appendLine()
                }
        }
        appendLine("## Coverage")
        appendLine()
        appendLine("Reviewed files (${result.reviewedFiles.size}):")
        result.reviewedFiles.forEach { appendLine("- ${inlineCode(it)}") }
        if (result.skippedFiles.isNotEmpty()) {
            appendLine()
            appendLine("Skipped files (${result.skippedFiles.size}):")
            result.skippedFiles.forEach { appendLine("- ${inlineCode(it.path)} — ${safe(it.reason)}") }
        }
        if (result.usedRagSources.isNotEmpty()) {
            appendLine()
            appendLine("## Repository context")
            appendLine()
            result.usedRagSources.forEach { source ->
                appendLine(
                    "- ${inlineCode("[${source.id}]")} ${inlineCode(source.source)} — ${safe(source.section)} " +
                        "(${inlineCode(source.chunkId)}, rank ${source.rank}, " +
                        "score ${"%.4f".format(java.util.Locale.ROOT, source.score)})",
                )
            }
        }
        appendLine()
        appendLine("## Diagnostics")
        appendLine()
        appendLine("- Model: ${inlineCode(diagnostics.model)}")
        appendLine("- Changed files: ${inlineCode(diagnostics.changedFileCount.toString())}")
        appendLine(
            "- Full diff: ${inlineCode(diagnostics.fullDiffCharacters.toString())} characters; " +
                "model input: ${inlineCode(diagnostics.promptDiffCharacters.toString())} characters",
        )
        appendLine(
            "- RAG sources: documentation ${inlineCode(diagnostics.documentationSourceCount.toString())}, " +
                "code ${inlineCode(diagnostics.codeSourceCount.toString())}",
        )
        appendLine("- Analysis: ${inlineCode(if (diagnostics.partial) "partial" else "full")}")
    }.let(redactor::redact)

    private fun safe(value: String): String = redactor.redact(value)
        .replace(AI_REVIEW_COMMENT_MARKER, "")
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("(", "\\(")
        .replace(")", "\\)")
        .replace("#", "\\#")
        .replace("+", "\\+")
        .replace("-", "\\-")
        .replace("!", "\\!")
        .replace("|", "\\|")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .trim()

    private fun inlineCode(value: String): String {
        val content = redactor.redact(value).replace('\r', ' ').replace('\n', ' ')
        val delimiter = "`".repeat((Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0) + 1)
        val padded = if (content.startsWith('`') || content.endsWith('`') || content.startsWith(' ') || content.endsWith(' ')) {
            " $content "
        } else {
            content
        }
        return "$delimiter$padded$delimiter"
    }
}

class ReviewArtifactStore(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun save(outputDirectory: Path, artifact: ReviewArtifact): Pair<Path, Path> {
        val directory = outputDirectory.toAbsolutePath().normalize()
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory)) { "Output directory не может быть symlink" }
        val markdown = directory.resolve("review.md")
        val jsonFile = directory.resolve("review.json")
        atomicWrite(markdown, artifact.markdown)
        atomicWrite(jsonFile, json.encodeToString(artifact))
        return markdown to jsonFile
    }

    private fun atomicWrite(target: Path, content: String) {
        require(!target.exists() || !Files.isSymbolicLink(target)) { "Output file не может быть symlink: ${target.fileName}" }
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
