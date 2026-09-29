package org.example.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.app.*
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

const val RAG_EVALUATION_FORMAT_VERSION = 1

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
)

@Serializable
data class RagEvaluationSource(
    val rank: Int,
    val score: Double,
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
)

@Serializable
data class ManualRagAssessment(
    val correctness: Int? = null,
    val completeness: Int? = null,
    val groundedness: Int? = null,
    val comment: String = "Требуется прозрачная ручная оценка по шкале 0–2.",
) {
    init {
        listOfNotNull(correctness, completeness, groundedness).forEach {
            require(it in 0..2) { "Ручная оценка должна быть от 0 до 2" }
        }
    }
}

@Serializable
data class RagEvaluationCaseResult(
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

@Serializable
data class RagEvaluationReport(
    val formatVersion: Int = RAG_EVALUATION_FORMAT_VERSION,
    val model: String,
    val note: String = "Retrieval/source/citation/usage metrics are automatic. Correctness, completeness and groundedness require manual 0–2 review; fake embeddings must not be treated as production quality.",
    val cases: List<RagEvaluationCaseResult>,
)

class RagEvaluationRunner(private val comparisonRunner: RagComparisonRunner) {
    suspend fun run(model: String, maxTokens: Int): RagEvaluationReport {
        val results = RAG_EVALUATION_CASES.map { evaluationCase ->
            val comparison = comparisonRunner.compare(evaluationCase.question, model, maxTokens)
            val baseline = comparison.branches.single { it.branch == RagBranch.BASELINE }
            val rag = comparison.branches.single { it.branch == RagBranch.RAG }
            val sources = rag.diagnostics?.sources.orEmpty()
            RagEvaluationCaseResult(
                id = evaluationCase.id,
                question = evaluationCase.question,
                expectation = evaluationCase.expectation,
                expectedSources = evaluationCase.expectedSources,
                expectedSectionContains = evaluationCase.expectedSectionContains,
                baseline = baseline.toEvaluationAnswer(),
                rag = rag.toEvaluationAnswer(),
                retrievedSources = sources.map(RagSourceDiagnostic::toEvaluationSource),
                expectedSourceFound = sources.any { source -> source.source in evaluationCase.expectedSources },
                citationsValid = rag.completion?.let { completion ->
                    runCatching { validateRagCitations(completion.content, sources.size) }.isSuccess
                } ?: false,
            )
        }
        return RagEvaluationReport(model = model, cases = results)
    }
}

class RagEvaluationReportStore(
    private val jsonFile: Path,
    private val markdownFile: Path,
) {
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    fun save(report: RagEvaluationReport) {
        require(report.formatVersion == RAG_EVALUATION_FORMAT_VERSION)
        writeAtomically(jsonFile, json.encodeToString(report))
        writeAtomically(markdownFile, report.toMarkdown())
    }

    fun load(): RagEvaluationReport {
        val report = json.decodeFromString<RagEvaluationReport>(Files.readString(jsonFile, StandardCharsets.UTF_8))
        require(report.formatVersion == RAG_EVALUATION_FORMAT_VERSION) { "Unsupported RAG evaluation format" }
        return report
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
    val usage = completion?.usage
    return RagEvaluationAnswer(
        content = completion?.content,
        error = error,
        model = completion?.model ?: tokenMetrics?.model,
        promptTokens = usage?.promptTokens,
        completionTokens = usage?.completionTokens,
        totalTokens = usage?.totalTokens,
        elapsedMillis = elapsedMillis,
    )
}

private fun RagSourceDiagnostic.toEvaluationSource() = RagEvaluationSource(
    rank, score, chunkId, source, title, section,
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
        appendLine("- Expected source found: ${result.expectedSourceFound}")
        appendLine("- Citations valid: ${result.citationsValid}")
        appendLine("- Baseline usage: input=${result.baseline.promptTokens ?: "n/a"}, output=${result.baseline.completionTokens ?: "n/a"}, elapsed=${result.baseline.elapsedMillis} ms")
        appendLine("- RAG usage: input=${result.rag.promptTokens ?: "n/a"}, output=${result.rag.completionTokens ?: "n/a"}, elapsed=${result.rag.elapsedMillis} ms")
        appendLine()
        appendLine("### Top-5 sources")
        appendLine()
        result.retrievedSources.forEach { source ->
            appendLine("${source.rank}. `${source.source}` · `${source.section}` · `${source.chunkId}` · score=${"%.6f".format(java.util.Locale.ROOT, source.score)}")
        }
        appendLine()
        appendLine("### БЕЗ RAG")
        appendLine()
        appendLine(result.baseline.content ?: "Ошибка: ${result.baseline.error}")
        appendLine()
        appendLine("### С RAG")
        appendLine()
        appendLine(result.rag.content ?: "Ошибка: ${result.rag.error}")
        appendLine()
        appendLine("### Ручная оценка 0–2")
        appendLine()
        appendLine("- correctness: ${result.manualAssessment.correctness ?: "pending"}")
        appendLine("- completeness: ${result.manualAssessment.completeness ?: "pending"}")
        appendLine("- groundedness: ${result.manualAssessment.groundedness ?: "pending"}")
        appendLine("- comment: ${result.manualAssessment.comment}")
        appendLine()
    }
}
