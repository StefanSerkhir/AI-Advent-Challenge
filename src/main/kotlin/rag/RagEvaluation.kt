package org.example.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.app.*
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

const val RAG_EVALUATION_FORMAT_VERSION = 3

@Serializable
data class RagEvaluationCase(
    val id: String,
    val question: String,
    val expectation: String,
    val expectedSources: List<String>,
    val expectedSectionContains: String? = null,
)

val RAG_EVALUATION_CASES: List<RagEvaluationCase> = listOf(
    RagEvaluationCase(
        "production-web-start",
        "Как запустить production web-приложение?",
        "Указать ./gradlew runWeb, адрес http://127.0.0.1:8080 и требования JDK 21+, Node.js 22.12+ и ключ выбранного провайдера.",
        listOf("README.md"),
        "Быстрый запуск",
    ),
    RagEvaluationCase(
        "fixture-start",
        "Как запустить детерминированный web fixture без платного API?",
        "Указать ./gradlew runWebFixture и пояснить, что test-source fixture/fake не использует внешнюю сеть или платный API.",
        listOf("README.md"),
    ),
    RagEvaluationCase(
        "production-entry-point",
        "Где находится production entry point web-приложения?",
        "Назвать org.example.web.WebMainKt и файл src/main/kotlin/web/WebMain.kt.",
        listOf("README.md", "docs/ARCHITECTURE.md"),
        "Запуск приложения",
    ),
    RagEvaluationCase(
        "memory-layer-order",
        "В каком порядке слои памяти попадают в контекст при MEMORY_LAYERS?",
        "Первое system message содержит инварианты, профиль и активную задачу; затем идут LONG_TERM, WORKING, SHORT_TERM и текущий prompt. Приоритет: safety/system, инварианты, текущий prompt, task state, профиль, остальные слои.",
        listOf("docs/ARCHITECTURE.md"),
        "Слои памяти",
    ),
    RagEvaluationCase(
        "branch-controls",
        "Когда доступны checkpoint и переключение веток?",
        "Только при ContextStrategy.BRANCHING в режиме unrestricted; при MEMORY_LAYERS эти API и элементы UI недоступны.",
        listOf("docs/ARCHITECTURE.md", "docs/WEB_API.md"),
    ),
    RagEvaluationCase(
        "mcp-agent-limits",
        "Какие ограничения действуют на локальные MCP-вызовы агента?",
        "Только OpenAI + unrestricted; каталог приходит из tools/list; максимум три tools/call; assistant/tool messages существуют только внутри запроса.",
        listOf("README.md", "docs/ARCHITECTURE.md"),
    ),
    RagEvaluationCase(
        "document-corpus",
        "Какие файлы входят в corpus локального документного индекса?",
        "Перечислить README.md, docs/**/*.md и docs/**/*.pdf, src/main/kotlin/**/*.kt, frontend/src/**/*.ts и **/*.tsx; исключения включают .git, build/output, node_modules, legacy-desktop, .env, .llm-*, symlinks и неразрешённые бинарные файлы.",
        listOf("docs/DOCUMENT_INDEXING.md"),
        "Corpus",
    ),
    RagEvaluationCase(
        "index-fixture-vs-production",
        "Чем runDocumentIndexFixture отличается от production-индексации?",
        "Fixture использует deterministic fake embeddings, временный каталог, не читает ключ и не вызывает сеть; его метрики проверяют механику, а не качество production embedding model.",
        listOf("docs/DOCUMENT_INDEXING.md"),
        "Безопасная воспроизводимая проверка",
    ),
    RagEvaluationCase(
        "operation-idempotency",
        "Как API предотвращает повторный платный запуск после неопределённой сетевой ошибки?",
        "Одинаковый requestId и тело возвращают исходный operationId; тот же ID с другим телом даёт duplicate_id; frontend повторяет исходную команду.",
        listOf("docs/WEB_API.md"),
        "Согласованность",
    ),
    RagEvaluationCase(
        "save-to-file-security",
        "Какие ограничения безопасности применяются к save_to_file?",
        "Запись только в configured output directory, безопасное имя без пути; запрещены absolute path, traversal, разделители, управляющие символы и существующая symlink-цель; запись атомарная с fallback.",
        listOf("README.md", "docs/ARCHITECTURE.md"),
    ),
)

@Serializable
data class RagEvaluationAnswer(
    val content: String? = null,
    val error: String? = null,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val elapsedMillis: Long,
    val estimatedCostUsd: Double? = null,
)

@Serializable
data class RagEvaluationSource(
    val rank: Int,
    val score: Double,
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val quotes: List<String> = emptyList(),
)

@Serializable
data class ManualRagAssessment(
    val correctness: Int? = null,
    val completeness: Int? = null,
    val groundedness: Int? = null,
    val support: Int? = null,
    val comment: String = "Требуется прозрачная ручная оценка по шкале 0–2, включая смысловую поддержку ответа цитатами.",
) {
    init {
        listOfNotNull(correctness, completeness, groundedness, support).forEach {
            require(it in 0..2) { "Ручная оценка должна быть от 0 до 2" }
        }
    }
}

@Serializable
data class RagEvaluationPipelineResult(
    val answer: RagEvaluationAnswer,
    val retrievedSources: List<RagEvaluationSource> = emptyList(),
    val sourcesPresent: Boolean = false,
    val quotesPresent: Boolean = false,
    val expectedSourceFound: Boolean = false,
    val citationsValid: Boolean = false,
    val quotesExact: Boolean = false,
    val abstained: Boolean = false,
    val abstentionReason: String? = null,
    val candidateCount: Int = 0,
    val filteredCount: Int = 0,
    val retrievalQuery: String? = null,
    val candidateLimit: Int = 0,
    val resultLimit: Int = 0,
    val minSimilarity: Double? = null,
    val rewrite: RagEvaluationRewriteMetrics? = null,
    val manualAssessment: ManualRagAssessment = ManualRagAssessment(),
)

@Serializable
data class RagEvaluationRewriteMetrics(
    val elapsedMillis: Long,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val estimatedCostUsd: Double? = null,
)

@Serializable
data class RagEvaluationCaseResult(
    val id: String,
    val question: String,
    val expectation: String,
    val expectedSources: List<String>,
    val expectedSectionContains: String? = null,
    val baseline: RagEvaluationAnswer,
    val raw: RagEvaluationPipelineResult,
    val enhanced: RagEvaluationPipelineResult,
)

@Serializable
data class RagEvaluationReport(
    val formatVersion: Int = RAG_EVALUATION_FORMAT_VERSION,
    val model: String,
    val note: String = "Source/quote/citation/exact-substring/usage metrics are automatic. Semantic support, correctness, completeness and groundedness require separate manual 0–2 review for raw and enhanced; fake embeddings must not be treated as production quality.",
    val cases: List<RagEvaluationCaseResult>,
)

class RagEvaluationRunner(
    private val comparisonRunner: RagComparisonRunner,
    private val ragCandidateLimit: Int = DEFAULT_RAG_CANDIDATE_LIMIT,
    private val ragResultLimit: Int = DEFAULT_RAG_RESULT_LIMIT,
    private val ragMinSimilarity: Double = DEFAULT_RAG_MIN_SIMILARITY,
) {
    suspend fun run(model: String, maxTokens: Int): RagEvaluationReport {
        val results = RAG_EVALUATION_CASES.map { evaluationCase ->
            val comparison = comparisonRunner.compare(
                evaluationCase.question,
                model,
                maxTokens,
                ragCandidateLimit = ragCandidateLimit,
                ragResultLimit = ragResultLimit,
                ragMinSimilarity = ragMinSimilarity,
            )
            val baseline = comparison.branches.single { it.branch == RagBranch.BASELINE }
            val raw = comparison.branches.single { it.branch == RagBranch.RAW }
            val enhanced = comparison.branches.single { it.branch == RagBranch.ENHANCED }
            RagEvaluationCaseResult(
                id = evaluationCase.id,
                question = evaluationCase.question,
                expectation = evaluationCase.expectation,
                expectedSources = evaluationCase.expectedSources,
                expectedSectionContains = evaluationCase.expectedSectionContains,
                baseline = baseline.toEvaluationAnswer(),
                raw = raw.toPipelineResult(evaluationCase.expectedSources),
                enhanced = enhanced.toPipelineResult(evaluationCase.expectedSources),
            )
        }
        return RagEvaluationReport(model = model, cases = results)
    }
}

class RagEvaluationReportStore(
    private val jsonFile: Path,
    private val markdownFile: Path,
) {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = true }

    fun save(report: RagEvaluationReport) {
        require(report.formatVersion == RAG_EVALUATION_FORMAT_VERSION)
        writeAtomically(jsonFile, json.encodeToString(report))
        writeAtomically(markdownFile, report.toMarkdown())
    }

    fun load(): RagEvaluationReport {
        val element = json.parseToJsonElement(Files.readString(jsonFile, StandardCharsets.UTF_8))
        val version = element.jsonObject["formatVersion"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("RAG evaluation formatVersion отсутствует")
        return when (version) {
            RAG_EVALUATION_FORMAT_VERSION -> json.decodeFromJsonElement(element)
            2 -> json.decodeFromJsonElement<RagEvaluationReport>(element).migrateFromV2()
            1 -> json.decodeFromJsonElement<RagEvaluationReportV1>(element).migrate()
            else -> error("Unsupported RAG evaluation format: $version")
        }
    }

    private fun writeAtomically(target: Path, content: String) {
        val absolute = target.toAbsolutePath()
        Files.createDirectories(absolute.parent)
        val temporary = Files.createTempFile(absolute.parent, ".rag-evaluation-", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun RagBranchResult.toEvaluationAnswer(): RagEvaluationAnswer {
    val usage = completion?.usage ?: tokenMetrics?.actualUsage
    return RagEvaluationAnswer(
        content = completion?.content,
        error = error,
        model = completion?.model ?: tokenMetrics?.model,
        promptTokens = usage?.promptTokens,
        completionTokens = usage?.completionTokens,
        totalTokens = usage?.totalTokens,
        elapsedMillis = elapsedMillis,
        estimatedCostUsd = tokenMetrics?.turnCostUsd?.toDouble(),
    )
}

private fun RagBranchResult.toPipelineResult(expectedSources: List<String>): RagEvaluationPipelineResult {
    val diagnostic = diagnostics
    val sources = diagnostic?.sources.orEmpty()
    val evidence = diagnostic?.evidence
    val evidenceByRank = evidence?.sources.orEmpty().associateBy { it.rank }
    val evidenceVerified = evidence?.status == RagEvidenceStatus.VERIFIED
    return RagEvaluationPipelineResult(
        answer = toEvaluationAnswer(),
        retrievedSources = sources.map { it.toEvaluationSource(evidenceByRank[it.rank]?.quotes.orEmpty()) },
        sourcesPresent = evidence?.sources?.isNotEmpty() == true,
        quotesPresent = evidence?.quoteCount?.let { it > 0 } == true,
        expectedSourceFound = sources.any { it.source in expectedSources },
        citationsValid = evidenceVerified,
        quotesExact = evidenceVerified && evidence.sources.all { it.quotes.isNotEmpty() },
        abstained = diagnostic?.abstained == true,
        abstentionReason = diagnostic?.abstentionReason,
        candidateCount = diagnostic?.candidateCount ?: 0,
        filteredCount = diagnostic?.filteredCount ?: 0,
        retrievalQuery = diagnostic?.retrievalQuery,
        candidateLimit = diagnostic?.candidateLimit ?: 0,
        resultLimit = diagnostic?.resultLimit ?: 0,
        minSimilarity = diagnostic?.minSimilarity,
        rewrite = diagnostic?.rewrite?.let {
            RagEvaluationRewriteMetrics(
                elapsedMillis = it.elapsedMillis,
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens,
                estimatedCostUsd = it.costUsd,
            )
        },
    )
}

private fun RagSourceDiagnostic.toEvaluationSource(quotes: List<String>) = RagEvaluationSource(
    rank, score, chunkId, source, title, section, quotes,
)

private fun RagEvaluationReport.toMarkdown(): String = buildString {
    appendLine("# RAG evaluation comparison")
    appendLine()
    appendLine("- Format version: `$formatVersion`")
    appendLine("- Model: `$model`")
    appendLine("- $note")
    appendLine()
    cases.forEachIndexed { index, result ->
        appendLine("## ${index + 1}. ${result.question}")
        appendLine()
        appendLine("**Expectation:** ${result.expectation}")
        appendLine()
        appendLine("**Expected sources:** ${result.expectedSources.joinToString { "`$it`" }}")
        appendLine()
        appendLine("| Pipeline | Query | Candidates → kept | Threshold | Sources | Quotes | Citations | Exact quotes | Expected source | Abstention | Usage | Rewrite usage/time/cost | Elapsed |")
        appendLine("|---|---|---:|---:|---|---|---|---|---|---|---:|---|---:|")
        appendPipelineRow("Raw RAG", result.raw)
        appendPipelineRow("Enhanced RAG", result.enhanced)
        appendLine()
        appendSources("Raw RAG sources", result.raw.retrievedSources)
        appendSources("Enhanced RAG sources", result.enhanced.retrievedSources)
        appendLine("### БЕЗ RAG")
        appendLine()
        appendLine(result.baseline.content ?: "Ошибка: ${result.baseline.error}")
        appendLine()
        appendLine("### RAG БЕЗ ФИЛЬТРА/REWRITE")
        appendLine()
        appendLine(result.raw.answer.content ?: "Ошибка: ${result.raw.answer.error}")
        appendLine()
        appendAssessment(result.raw.manualAssessment)
        appendLine("### УЛУЧШЕННЫЙ RAG")
        appendLine()
        appendLine(result.enhanced.answer.content ?: "Ошибка: ${result.enhanced.answer.error}")
        appendLine()
        appendAssessment(result.enhanced.manualAssessment)
    }
}

private fun StringBuilder.appendPipelineRow(label: String, pipeline: RagEvaluationPipelineResult) {
    val rewrite = pipeline.rewrite?.let {
        "${it.totalTokens ?: "n/a"} tok / ${it.elapsedMillis} ms / ${it.estimatedCostUsd ?: "n/a"} USD"
    } ?: "n/a"
    appendLine(
        "| $label | `${pipeline.retrievalQuery.orEmpty().replace("|", "\\|")}` | " +
            "${pipeline.candidateCount} → ${pipeline.filteredCount} | ${pipeline.minSimilarity?.toString() ?: "n/a"} | " +
            "${pipeline.sourcesPresent} | ${pipeline.quotesPresent} | ${pipeline.citationsValid} | ${pipeline.quotesExact} | " +
            "${pipeline.expectedSourceFound} | ${pipeline.abstentionReason ?: "no"} | ${pipeline.answer.totalTokens ?: "n/a"} | $rewrite | ${pipeline.answer.elapsedMillis} ms |",
    )
}

private fun StringBuilder.appendSources(title: String, sources: List<RagEvaluationSource>) {
    appendLine("### $title")
    appendLine()
    if (sources.isEmpty()) appendLine("Нет источников.")
    sources.forEach { source ->
        appendLine("${source.rank}. `${source.source}` · `${source.section}` · `${source.chunkId}` · score=${"%.6f".format(Locale.ROOT, source.score)}")
        source.quotes.forEach { quote -> appendLine("   - [S${source.rank}] «$quote»") }
    }
    appendLine()
}

private fun StringBuilder.appendAssessment(assessment: ManualRagAssessment) {
    appendLine("Ручная оценка 0–2: correctness=${assessment.correctness ?: "pending"}, completeness=${assessment.completeness ?: "pending"}, groundedness=${assessment.groundedness ?: "pending"}, support=${assessment.support ?: "pending"}.")
    appendLine()
    appendLine("Комментарий: ${assessment.comment}")
    appendLine()
}

@Serializable
private data class RagEvaluationReportV1(
    val formatVersion: Int,
    val model: String,
    val note: String,
    val cases: List<RagEvaluationCaseResultV1>,
)

@Serializable
private data class RagEvaluationCaseResultV1(
    val id: String,
    val question: String,
    val expectation: String,
    val expectedSources: List<String>,
    val expectedSectionContains: String? = null,
    val baseline: RagEvaluationAnswer,
    val rag: RagEvaluationAnswer,
    val retrievedSources: List<RagEvaluationSource>,
    val expectedSourceFound: Boolean,
    val citationsValid: Boolean,
    val manualAssessment: ManualRagAssessment = ManualRagAssessment(),
)

private fun RagEvaluationReportV1.migrate() = RagEvaluationReport(
    model = model,
    note = "$note Migrated from format v1; enhanced results were not present in the original report.",
    cases = cases.map { old ->
        RagEvaluationCaseResult(
            id = old.id,
            question = old.question,
            expectation = old.expectation,
            expectedSources = old.expectedSources,
            expectedSectionContains = old.expectedSectionContains,
            baseline = old.baseline,
            raw = RagEvaluationPipelineResult(
                answer = old.rag,
                retrievedSources = old.retrievedSources,
                sourcesPresent = old.retrievedSources.isNotEmpty(),
                expectedSourceFound = old.expectedSourceFound,
                citationsValid = old.citationsValid,
                quotesPresent = false,
                quotesExact = false,
                candidateCount = old.retrievedSources.size,
                filteredCount = old.retrievedSources.size,
                retrievalQuery = old.question,
                candidateLimit = old.retrievedSources.size,
                resultLimit = old.retrievedSources.size,
                manualAssessment = old.manualAssessment,
            ),
            enhanced = RagEvaluationPipelineResult(
                answer = RagEvaluationAnswer(error = "Enhanced pipeline отсутствует в отчёте формата v1.", elapsedMillis = 0),
            ),
        )
    },
)

private fun RagEvaluationReport.migrateFromV2() = copy(
    formatVersion = RAG_EVALUATION_FORMAT_VERSION,
    note = "$note Migrated from format v2; historical reports did not store verified quotes, so quotesPresent/quotesExact remain false.",
    cases = cases.map { old ->
        old.copy(
            raw = old.raw.copy(
                sourcesPresent = old.raw.retrievedSources.isNotEmpty(),
                quotesPresent = false,
                quotesExact = false,
            ),
            enhanced = old.enhanced.copy(
                sourcesPresent = old.enhanced.retrievedSources.isNotEmpty(),
                quotesPresent = false,
                quotesExact = false,
            ),
        )
    },
)
