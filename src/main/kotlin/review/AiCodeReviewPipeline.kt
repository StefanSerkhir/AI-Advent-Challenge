package org.example.review

import org.example.llm.CompletionOptions
import org.example.llm.LlmClient
import org.example.llm.ReasoningEffort
import java.nio.file.Path

fun interface PullRequestDiffSource {
    fun load(metadata: PullRequestMetadata): PullRequestDiff
}

fun interface ReviewCommentPublisher {
    suspend fun upsert(markdown: String)
}

class AiCodeReviewPipeline(
    private val diffSource: PullRequestDiffSource,
    private val ragProvider: ReviewRagProvider,
    private val llmClient: LlmClient,
    private val model: String,
    private val outputDirectory: Path,
    private val publisher: ReviewCommentPublisher? = null,
    private val diffLimiter: ReviewDiffLimiter = ReviewDiffLimiter(),
    private val queryBuilder: ReviewRetrievalQueryBuilder = ReviewRetrievalQueryBuilder(),
    private val promptBuilder: ReviewPromptBuilder = ReviewPromptBuilder(),
    private val validator: ReviewOutputValidator = ReviewOutputValidator(),
    private val renderer: ReviewMarkdownRenderer = ReviewMarkdownRenderer(),
    private val artifactStore: ReviewArtifactStore = ReviewArtifactStore(),
    private val maxCompletionTokens: Int = DEFAULT_REVIEW_MAX_TOKENS,
) {
    init {
        require(model.matches(Regex("gpt-5\\.6-(?:luna|terra|sol)"))) {
            "AI code review model должен быть одной из GPT-5.6 моделей проекта"
        }
        require(maxCompletionTokens > 0)
    }

    suspend fun run(metadata: PullRequestMetadata): ReviewArtifact {
        val diff = diffSource.load(metadata)
        require(diff.files.isNotEmpty()) { "Pull request не содержит изменённых файлов" }
        val bounded = diffLimiter.limit(diff)
        val query = queryBuilder.build(metadata, diff)
        val ragSources = ragProvider.retrieve(query)
        val prompt = promptBuilder.build(metadata, diff.files, bounded, ragSources)
        val result = completeWithOneRepair(prompt, bounded, diff.files.map(ChangedFile::path).toSet(), ragSources)
        val diagnostics = ReviewDiagnostics(
            model = model,
            analysedCommit = metadata.headSha,
            changedFileCount = diff.files.size,
            fullDiffCharacters = bounded.fullCharacterCount,
            promptDiffCharacters = bounded.promptDiff.length,
            documentationSourceCount = ragSources.count { it.domain == RagDomain.DOCUMENTATION },
            codeSourceCount = ragSources.count { it.domain == RagDomain.CODE },
            partial = bounded.partial,
        )
        val markdown = renderer.render(result, diagnostics)
        val artifact = ReviewArtifact(result, diagnostics, markdown)
        artifactStore.save(outputDirectory, artifact)
        publisher?.upsert(markdown)
        return artifact
    }

    private suspend fun completeWithOneRepair(
        prompt: ReviewPrompt,
        boundedDiff: BoundedReviewDiff,
        allChangedPaths: Set<String>,
        ragSources: List<ReviewRagSource>,
    ): ReviewResult {
        val first = complete(prompt)
        return try {
            validator.decodeAndValidate(first, boundedDiff, allChangedPaths, ragSources)
        } catch (firstError: ReviewValidationException) {
            val repairedPrompt = promptBuilder.repair(prompt, first, firstError.message.orEmpty())
            val repaired = complete(repairedPrompt)
            try {
                validator.decodeAndValidate(repaired, boundedDiff, allChangedPaths, ragSources)
            } catch (secondError: ReviewValidationException) {
                throw ReviewValidationException(
                    "Model returned invalid structured output twice; no review was published: ${secondError.message}",
                )
            }
        }
    }

    private suspend fun complete(prompt: ReviewPrompt): String = llmClient.complete(
        messages = prompt.messages,
        options = CompletionOptions(
            maxTokens = maxCompletionTokens,
            reasoningEffort = ReasoningEffort.MEDIUM,
            structuredOutput = prompt.structuredOutput,
        ),
    ).content
}
