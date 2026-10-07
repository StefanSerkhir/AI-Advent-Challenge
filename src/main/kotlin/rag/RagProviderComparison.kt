package org.example.rag

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.example.app.RagEvidenceStatus
import org.example.app.ragMessages
import org.example.app.validateRagCitations
import org.example.indexing.DocumentRetriever
import org.example.indexing.RetrievedDocumentChunk
import org.example.llm.CompletionOptions
import org.example.llm.LlmClient
import org.example.tokens.ModelContextProfiles
import org.example.tokens.TokenCostCalculator
import java.util.*
import kotlin.math.ceil

const val DEFAULT_RAG_STABILITY_REPEATS = 3
const val DEFAULT_RAG_PROVIDER_TIMEOUT_MILLIS = 180_000L

data class RagGenerationTarget(
    val provider: String,
    val model: String,
    val clientProvider: () -> LlmClient,
)

@Serializable
data class RagProviderRun(
    val caseId: String,
    val repetition: Int,
    val provider: String,
    val model: String,
    val success: Boolean,
    val error: String? = null,
    val answer: String? = null,
    val timedOut: Boolean = false,
    val endToEndElapsedMillis: Long,
    val queryEmbeddingElapsedMillis: Long,
    val retrievalElapsedMillis: Long,
    val generationElapsedMillis: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val estimatedCostUsd: Double? = null,
    val retrievedSources: List<String>,
    val retrievedChunkIds: List<String>,
    val expectedSourceHit: Boolean,
    val citationsValid: Boolean,
    val quotesExact: Boolean,
    val abstained: Boolean,
    val manualAssessment: ManualRagAssessment? = null,
)

@Serializable
data class RagProviderAggregate(
    val provider: String,
    val model: String,
    val status: String,
    val skipReason: String? = null,
    val measuredRuns: Int = 0,
    val successfulRuns: Int = 0,
    val errorCount: Int = 0,
    val timeoutCount: Int = 0,
    val completionSuccessRate: Double? = null,
    val verifiedCitationRate: Double? = null,
    val retrievedChunkIdStability: Double? = null,
    val latencyP50Millis: Long? = null,
    val latencyP95Millis: Long? = null,
    val latencyMinMillis: Long? = null,
    val latencyMaxMillis: Long? = null,
    val latencySpreadMillis: Long? = null,
)

@Serializable
data class RagProviderComparison(
    val methodology: String = "Retrieval is local. Within each case/repetition every generation provider receives the same frozen chunks, prompt and max tokens. Warm-up calls are excluded from measured latency.",
    val representativeCaseIds: List<String>,
    val warmUpRunsPerAvailableProvider: Int,
    val measuredRepeats: Int,
    val runs: List<RagProviderRun>,
    val aggregates: List<RagProviderAggregate>,
)

class RagProviderComparisonRunner(
    private val retriever: DocumentRetriever,
    private val availableTargets: List<RagGenerationTarget>,
    private val skippedTargets: List<Pair<Pair<String, String>, String>> = emptyList(),
    private val resultLimit: Int,
    private val repeats: Int = DEFAULT_RAG_STABILITY_REPEATS,
    private val timeoutMillis: Long = DEFAULT_RAG_PROVIDER_TIMEOUT_MILLIS,
    private val errorMessage: (Throwable) -> String = { it.message ?: "Неизвестная ошибка" },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(availableTargets.isNotEmpty()) { "Локальный generation target обязателен" }
        require(resultLimit > 0)
        require(repeats >= 3) { "Для stability evaluation требуется не менее трёх повторов" }
        require(timeoutMillis > 0)
    }

    suspend fun run(
        cases: List<RagEvaluationCase>,
        maxTokens: Int,
    ): RagProviderComparison {
        require(cases.isNotEmpty())
        require(maxTokens > 0)

        val warmUpRetrieval = retriever.retrieve(cases.first().question, resultLimit)
        availableTargets.forEach { target ->
            runGeneration(target, cases.first(), warmUpRetrieval.chunks, maxTokens, repetition = 0)
        }

        val runs = mutableListOf<RagProviderRun>()
        cases.forEach { evaluationCase ->
            repeat(repeats) { repetitionIndex ->
                val retrieval = retriever.retrieve(evaluationCase.question, resultLimit)
                availableTargets.forEach { target ->
                    runs += runGeneration(
                        target = target,
                        evaluationCase = evaluationCase,
                        chunks = retrieval.chunks,
                        maxTokens = maxTokens,
                        repetition = repetitionIndex + 1,
                        queryEmbeddingElapsedMillis = retrieval.queryEmbeddingElapsedMillis,
                        retrievalElapsedMillis = retrieval.retrievalElapsedMillis,
                    )
                }
            }
        }

        val aggregates = availableTargets.map { target -> aggregate(target, runs.filter { it.provider == target.provider && it.model == target.model }) } +
            skippedTargets.map { (identity, reason) ->
                RagProviderAggregate(identity.first, identity.second, status = "skipped", skipReason = reason)
            }
        return RagProviderComparison(
            representativeCaseIds = cases.map(RagEvaluationCase::id),
            warmUpRunsPerAvailableProvider = 1,
            measuredRepeats = repeats,
            runs = runs,
            aggregates = aggregates,
        )
    }

    private suspend fun runGeneration(
        target: RagGenerationTarget,
        evaluationCase: RagEvaluationCase,
        chunks: List<RetrievedDocumentChunk>,
        maxTokens: Int,
        repetition: Int,
        queryEmbeddingElapsedMillis: Long = 0,
        retrievalElapsedMillis: Long = 0,
    ): RagProviderRun {
        val started = nanoTime()
        val generationStarted = nanoTime()
        var receivedAnswer: String? = null
        var generationElapsed: Long? = null
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var totalTokens: Int? = null
        var estimatedCostUsd: Double? = null
        return try {
            val completion = withTimeout(timeoutMillis) {
                target.clientProvider().complete(
                    ragMessages(
                        evaluationCase.question,
                        org.example.indexing.DocumentRetrievalResult(
                            strategy = org.example.indexing.ChunkingKind.STRUCTURED,
                            embeddingModel = "frozen-provider-comparison",
                            manifestHash = "request-local",
                            chunks = chunks,
                        ),
                    ),
                    CompletionOptions(maxTokens = maxTokens),
                )
            }
            receivedAnswer = completion.content
            generationElapsed = elapsedMillis(generationStarted)
            promptTokens = completion.usage?.promptTokens
            completionTokens = completion.usage?.completionTokens
            totalTokens = completion.usage?.totalTokens
            val profile = completion.model?.let(ModelContextProfiles::find) ?: ModelContextProfiles.find(target.model)
            estimatedCostUsd = TokenCostCalculator().calculate(completion.usage, profile)?.toDouble()
            val abstained = completion.content.trimStart().startsWith("Не знаю", ignoreCase = true)
            val validated = if (abstained) null else validateRagCitations(completion.content, chunks)
            RagProviderRun(
                caseId = evaluationCase.id,
                repetition = repetition,
                provider = target.provider,
                model = completion.model ?: target.model,
                success = true,
                answer = completion.content,
                endToEndElapsedMillis = elapsedMillis(started) + retrievalElapsedMillis,
                queryEmbeddingElapsedMillis = queryEmbeddingElapsedMillis,
                retrievalElapsedMillis = retrievalElapsedMillis,
                generationElapsedMillis = generationElapsed,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                estimatedCostUsd = estimatedCostUsd,
                retrievedSources = chunks.map(RetrievedDocumentChunk::source),
                retrievedChunkIds = chunks.map(RetrievedDocumentChunk::chunkId),
                expectedSourceHit = chunks.any { it.source in evaluationCase.expectedSources },
                citationsValid = validated?.evidence?.status == RagEvidenceStatus.VERIFIED,
                quotesExact = validated?.evidence?.sources?.let { sources ->
                    sources.isNotEmpty() && sources.all { it.quotes.isNotEmpty() }
                } == true,
                abstained = abstained,
                manualAssessment = ManualRagAssessment(),
            )
        } catch (error: TimeoutCancellationException) {
            failedRun(target, evaluationCase, chunks, repetition, started, queryEmbeddingElapsedMillis, retrievalElapsedMillis, true, "timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failedRun(
                target, evaluationCase, chunks, repetition, started, queryEmbeddingElapsedMillis, retrievalElapsedMillis,
                false, errorMessage(error), receivedAnswer, generationElapsed, promptTokens, completionTokens, totalTokens,
                estimatedCostUsd,
            )
        }
    }

    private fun failedRun(
        target: RagGenerationTarget,
        evaluationCase: RagEvaluationCase,
        chunks: List<RetrievedDocumentChunk>,
        repetition: Int,
        started: Long,
        queryEmbeddingElapsedMillis: Long,
        retrievalElapsedMillis: Long,
        timedOut: Boolean,
        error: String,
        answer: String? = null,
        generationElapsedMillis: Long? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        totalTokens: Int? = null,
        estimatedCostUsd: Double? = null,
    ) = RagProviderRun(
        caseId = evaluationCase.id,
        repetition = repetition,
        provider = target.provider,
        model = target.model,
        success = false,
        error = error.take(500),
        answer = answer,
        timedOut = timedOut,
        endToEndElapsedMillis = elapsedMillis(started) + retrievalElapsedMillis,
        queryEmbeddingElapsedMillis = queryEmbeddingElapsedMillis,
        retrievalElapsedMillis = retrievalElapsedMillis,
        generationElapsedMillis = generationElapsedMillis,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        estimatedCostUsd = estimatedCostUsd,
        retrievedSources = chunks.map(RetrievedDocumentChunk::source),
        retrievedChunkIds = chunks.map(RetrievedDocumentChunk::chunkId),
        expectedSourceHit = chunks.any { it.source in evaluationCase.expectedSources },
        citationsValid = false,
        quotesExact = false,
        abstained = false,
        manualAssessment = answer?.let { ManualRagAssessment() },
    )

    private fun aggregate(target: RagGenerationTarget, runs: List<RagProviderRun>): RagProviderAggregate {
        val successes = runs.filter(RagProviderRun::success)
        val latencies = successes.map(RagProviderRun::endToEndElapsedMillis).sorted()
        val stabilityGroups = runs.groupBy(RagProviderRun::caseId).values.map { caseRuns ->
            val retrievedIds = caseRuns.map { it.retrievedChunkIds }
            if (retrievedIds.isEmpty()) 0.0 else retrievedIds.count { it == retrievedIds.first() }.toDouble() / retrievedIds.size
        }
        return RagProviderAggregate(
            provider = target.provider,
            model = target.model,
            status = "completed",
            measuredRuns = runs.size,
            successfulRuns = successes.size,
            errorCount = runs.count { !it.success && !it.timedOut },
            timeoutCount = runs.count(RagProviderRun::timedOut),
            completionSuccessRate = ratio(successes.size, runs.size),
            verifiedCitationRate = ratio(successes.count { it.citationsValid && it.quotesExact }, successes.size),
            retrievedChunkIdStability = stabilityGroups.takeIf(List<Double>::isNotEmpty)?.average(),
            latencyP50Millis = percentile(latencies, 0.50),
            latencyP95Millis = percentile(latencies, 0.95),
            latencyMinMillis = latencies.firstOrNull(),
            latencyMaxMillis = latencies.lastOrNull(),
            latencySpreadMillis = latencies.takeIf { it.isNotEmpty() }?.let { it.last() - it.first() },
        )
    }

    private fun elapsedMillis(started: Long): Long = (nanoTime() - started).coerceAtLeast(0L) / 1_000_000
    private fun ratio(numerator: Int, denominator: Int): Double? = denominator.takeIf { it > 0 }?.let { numerator.toDouble() / it }
    private fun percentile(sorted: List<Long>, percentile: Double): Long? = sorted.takeIf { it.isNotEmpty() }?.let { values ->
        values[(ceil(values.size * percentile).toInt() - 1).coerceIn(0, values.lastIndex)]
    }
}

internal fun StringBuilder.appendProviderComparison(comparison: RagProviderComparison?) {
    appendLine("## Local vs cloud generation stability")
    appendLine()
    if (comparison == null) {
        appendLine("Provider comparison was not executed.")
        appendLine()
        return
    }
    appendLine(comparison.methodology)
    appendLine()
    appendLine("Representative cases: ${comparison.representativeCaseIds.joinToString()}, warm-up: ${comparison.warmUpRunsPerAvailableProvider}, measured repeats: ${comparison.measuredRepeats}.")
    appendLine()
    appendLine("| Provider/model | Status | Runs | Success | Errors/timeouts | Verified citations | Chunk stability | p50 / p95 | Range |")
    appendLine("|---|---|---:|---:|---:|---:|---:|---:|---:|")
    comparison.aggregates.forEach { aggregate ->
        appendLine(
            "| `${aggregate.provider}/${aggregate.model}` | ${aggregate.status}${aggregate.skipReason?.let { ": ${it.replace("|", "\\|")}" }.orEmpty()} | " +
                "${aggregate.measuredRuns} | ${aggregate.completionSuccessRate?.let { formatPercent(it) } ?: "n/a"} | " +
                "${aggregate.errorCount}/${aggregate.timeoutCount} | ${aggregate.verifiedCitationRate?.let { formatPercent(it) } ?: "n/a"} | " +
                "${aggregate.retrievedChunkIdStability?.let { formatPercent(it) } ?: "n/a"} | " +
                "${aggregate.latencyP50Millis ?: "n/a"} / ${aggregate.latencyP95Millis ?: "n/a"} ms | " +
                "${aggregate.latencyMinMillis ?: "n/a"}..${aggregate.latencyMaxMillis ?: "n/a"} ms |",
        )
    }
    appendLine()
    appendLine("### Measured provider runs")
    appendLine()
    appendLine("| Case | Repeat | Provider/model | Status | End-to-end | Embedding / retrieval / generation | Expected source | Evidence |")
    appendLine("|---|---:|---|---|---:|---:|---:|---|")
    comparison.runs.forEach { run ->
        val status = when {
            run.timedOut -> "timeout"
            run.success -> "success"
            else -> "error"
        }
        val evidence = when {
            run.abstained -> "abstained"
            run.citationsValid && run.quotesExact -> "verified"
            else -> "not verified"
        }
        appendLine(
            "| `${run.caseId}` | ${run.repetition} | `${run.provider}/${run.model}` | $status | ${run.endToEndElapsedMillis} ms | " +
                "${run.queryEmbeddingElapsedMillis} / ${run.retrievalElapsedMillis} / ${run.generationElapsedMillis ?: "n/a"} ms | " +
                "${if (run.expectedSourceHit) "yes" else "no"} | $evidence |",
        )
    }
    appendLine()
}

private fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100.0)
