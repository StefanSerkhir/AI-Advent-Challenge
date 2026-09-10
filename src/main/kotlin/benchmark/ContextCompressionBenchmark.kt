package org.example.benchmark

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.agent.*
import org.example.llm.*
import org.example.network.createHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.math.roundToLong

private const val CONTROL_QUESTION = "CONTROL QUESTION: Return every remembered project fact and the unresolved question."
private const val SYSTEM_INSTRUCTIONS = """You are a memory benchmark agent.
Acknowledge ordinary memory statements briefly. For the CONTROL QUESTION, report only facts present in the supplied conversation context, including changed decisions and the open question. Do not invent facts."""
private val BENCHMARK_JSON = Json { prettyPrint = true; encodeDefaults = true }

fun main() = runBlocking {
    val real = System.getenv("CONTEXT_BENCHMARK_REAL")?.toBooleanStrictOrNull() == true
    val model = System.getenv("CONTEXT_BENCHMARK_MODEL") ?: "gpt-4.1-mini"
    val httpClient = if (real) createHttpClient() else null
    try {
        val client: LlmClient = if (real) {
            val key = System.getenv("OPENAI_API_KEY")
                ?: error("CONTEXT_BENCHMARK_REAL=true requires OPENAI_API_KEY")
            createLlmClient(LlmKind.OPENAI, key, requireNotNull(httpClient), model)
        } else {
            DeterministicBenchmarkClient()
        }
        val benchmarkConfig = ContextCompressionConfig.fromEnvironment().copy(systemInstructions = SYSTEM_INSTRUCTIONS)
        val rows = listOf(
            runMode("baseline", benchmarkConfig.copy(enabled = false), client),
            runMode("compressed", benchmarkConfig.copy(enabled = true), client),
        )
        val baseline = rows.first().totalTokens
        val completed = rows.map { row ->
            row.copy(savingPercent = if (row.mode == "baseline" || baseline == 0L) 0.0 else
                (baseline - row.totalTokens).toDouble() / baseline * 100.0)
        }
        val report = BenchmarkReport(
            backend = if (real) "openai" else "deterministic-local",
            model = if (real) model else "deterministic-memory-model-v1",
            recentMessagesLimit = benchmarkConfig.recentMessagesLimit,
            summarizationBatchSize = benchmarkConfig.summarizationBatchSize,
            scenarioTurns = SCENARIO.size + 1,
            results = completed,
        )
        val output = Path.of(System.getenv("CONTEXT_BENCHMARK_OUTPUT") ?: "benchmark-results.json").toAbsolutePath()
        Files.writeString(output, BENCHMARK_JSON.encodeToString(report))
        printTable(report)
        println("\nJSON: $output")
    } finally {
        httpClient?.close()
    }
}

private suspend fun runMode(mode: String, config: ContextCompressionConfig, client: LlmClient): BenchmarkRow {
    val manager = ContextManager(
        config,
        InMemoryContextStateStore(),
        if (client is DeterministicBenchmarkClient) DeterministicSummarizer()
        else LlmHistorySummarizer({ client }, CompletionOptions(maxTokens = 300, temperature = 0.0)),
    )
    val agent = ContextManagingAgent("benchmark-$mode", manager) { client }
    val options = CompletionOptions(maxTokens = 300, temperature = 0.0)
    SCENARIO.forEach { agent.respond(it, options) }
    val answer = agent.respond(CONTROL_QUESTION, options).completion.content
    val checks = qualityChecks(answer)
    val state = manager.state("benchmark-$mode")
    return BenchmarkRow(
        mode = mode,
        qualityPassed = checks.count(QualityCheck::passed),
        qualityTotal = checks.size,
        qualityPercent = checks.count(QualityCheck::passed).toDouble() / checks.size * 100.0,
        checks = checks,
        mainInputTokens = state.stats.mainInputTokens,
        mainOutputTokens = state.stats.mainOutputTokens,
        mainTotalTokens = state.stats.mainTotalTokens,
        summaryInputTokens = state.stats.summaryInputTokens,
        summaryOutputTokens = state.stats.summaryOutputTokens,
        summaryTotalTokens = state.stats.summaryTotalTokens,
        totalTokens = state.stats.totalTokens,
        averageMainInputTokens = state.stats.averageMainInputTokens,
        mainRequests = state.stats.mainRequests,
        summarizationRequests = state.stats.summarizationRequests,
        answer = answer,
    )
}

private fun qualityChecks(answer: String): List<QualityCheck> {
    val normalized = answer.lowercase()
    fun contains(name: String, vararg values: String) = QualityCheck(name, values.all { it.lowercase() in normalized })
    return listOf(
        contains("early facts", "Atlas", "Mira"),
        contains("exact budget", "125000"),
        contains("region", "eu-west-1"),
        contains("deadline", "2027-02-14"),
        contains("technical constraint", "Kotlin", "PII"),
        contains("changed decision", "PostgreSQL", "replaces MongoDB"),
        contains("open question", "30 or 90 days"),
        QualityCheck("no invented Redis fact", "redis" !in normalized),
    )
}

private fun printTable(report: BenchmarkReport) {
    println("| Mode | Quality | Main input | Main output | Summary tokens | Total tokens | Saving |")
    println("|---|---:|---:|---:|---:|---:|---:|")
    report.results.forEach { row ->
        println("| ${row.mode} | ${row.qualityPassed}/${row.qualityTotal} | ${row.mainInputTokens} | ${row.mainOutputTokens} | ${row.summaryTotalTokens} | ${row.totalTokens} | ${String.format(Locale.US, "%.2f", row.savingPercent)}% |")
    }
}

@Serializable data class BenchmarkReport(
    val backend: String,
    val model: String,
    val recentMessagesLimit: Int,
    val summarizationBatchSize: Int,
    val scenarioTurns: Int,
    val results: List<BenchmarkRow>,
)

@Serializable data class BenchmarkRow(
    val mode: String,
    val qualityPassed: Int,
    val qualityTotal: Int,
    val qualityPercent: Double,
    val checks: List<QualityCheck>,
    val mainInputTokens: Long,
    val mainOutputTokens: Long,
    val mainTotalTokens: Long,
    val summaryInputTokens: Long,
    val summaryOutputTokens: Long,
    val summaryTotalTokens: Long,
    val totalTokens: Long,
    val averageMainInputTokens: Double,
    val mainRequests: Int,
    val summarizationRequests: Int,
    val savingPercent: Double = 0.0,
    val answer: String,
)

@Serializable data class QualityCheck(val name: String, val passed: Boolean)

private class DeterministicBenchmarkClient : LlmClient {
    override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
        val control = messages.last().content == CONTROL_QUESTION
        val visible = messages.joinToString("\n", transform = LlmMessage::content)
        val answer = if (!control) "Acknowledged." else buildList {
            if ("Atlas" in visible && "Mira" in visible) add("Project Atlas is owned by Mira.")
            if ("125000" in visible) add("Budget: 125000 USD.")
            if ("eu-west-1" in visible) add("Region: eu-west-1.")
            if ("2027-02-14" in visible) add("Deadline: 2027-02-14.")
            if ("Kotlin" in visible) add("Implementation must use Kotlin.")
            if ("PII" in visible) add("Constraint: never store PII in logs.")
            if ("PostgreSQL replaces MongoDB" in visible) add("Decision: PostgreSQL replaces MongoDB.")
            if ("30 or 90 days" in visible) add("Open question: retain audit events for 30 or 90 days?")
        }.joinToString("\n")
        val input = tokenCount(visible)
        val output = tokenCount(answer)
        return CompletionResult(answer, "stop", TokenUsage(input, output, input + output), "deterministic-memory-model-v1")
    }
}

private class DeterministicSummarizer : HistorySummarizer {
    override suspend fun summarize(previousSummary: String, messages: List<LlmMessage>): CompletionResult {
        val newFacts = messages.map(LlmMessage::content).filter { "[MEMORY]" in it }
        val summary = (previousSummary.lines() + newFacts)
            .filter(String::isNotBlank).distinct().joinToString("\n")
        val inputText = previousSummary + messages.joinToString("\n", transform = LlmMessage::content)
        val input = tokenCount(inputText)
        val output = tokenCount(summary)
        return CompletionResult(summary, "stop", TokenUsage(input, output, input + output), "deterministic-memory-model-v1")
    }
}

private fun tokenCount(text: String): Int = (text.length / 4.0).roundToLong().toInt().coerceAtLeast(1)

private val SCENARIO = listOf(
    "[MEMORY] Project Atlas is owned by Mira.",
    "[MEMORY] The approved budget is exactly 125000 USD.",
    "[MEMORY] Deployment region is eu-west-1.",
    "[MEMORY] Initial database decision: MongoDB.",
    "[MEMORY] Constraint: never store PII in logs.",
    "Discuss a verbose non-memory status update for sprint planning and stakeholder coordination.",
    "Record that routine smoke tests ran successfully; this is not a new project constraint.",
    "[MEMORY] Delivery deadline is 2027-02-14.",
    "[MEMORY] Implementation language must be Kotlin.",
    "[MEMORY] Changed decision: PostgreSQL replaces MongoDB.",
    "Discuss deployment notes without adding infrastructure requirements.",
    "Acknowledge that the team reviewed monitoring dashboards.",
    "Provide a short generic progress acknowledgement.",
    "Note that ordinary documentation cleanup is underway.",
    "[MEMORY] Open question: retain audit events for 30 or 90 days?",
    "Acknowledge the final planning checkpoint without inventing facts.",
) + (1..20).map { index ->
    "Routine update $index: the team reviewed ordinary sprint notes, status dashboards, test logs, and documentation wording. " +
        "This deliberately verbose benchmark turn adds no project fact, decision, constraint, identifier, number, or open question."
}
