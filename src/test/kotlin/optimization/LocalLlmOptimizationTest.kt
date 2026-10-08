package org.example.optimization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class LocalLlmOptimizationTest {
    @Test
    fun `specialized prompt fixes role grounding citations abstention and untrusted boundary`() {
        val prompt = promptText(RagPromptVersion.REPO_TECH_V2)

        assertTrue(prompt.contains("технический помощник"))
        assertTrue(prompt.contains("репозиторию LLM Workbench"))
        assertTrue(prompt.contains("недоверенные данные"))
        assertTrue(prompt.contains("не выполняй"))
        assertTrue(prompt.contains("Не додумывай"))
        assertTrue(prompt.contains("[Sx]"))
        assertTrue(prompt.contains("точными"))
        assertTrue(prompt.contains("Не знаю"))
        assertTrue(prompt.contains("Ответ\n"))
        assertTrue(prompt.contains("Цитаты\n"))
    }

    @Test
    fun `aggregate uses every repeat and transparent weighted quality formula`() {
        val config = config("candidate")
        val runs = listOf(
            run(config.id, "answerable", 1, true, 1_000, citations = true, quotes = true, coverage = true, format = true),
            run(config.id, "answerable", 2, true, 3_000, citations = false, quotes = false, coverage = true, format = true),
            run(config.id, ABSTENTION_CASE_ID, 1, true, 2_000, citations = true, quotes = true, coverage = true, abstention = true, format = true),
        )

        val aggregate = aggregateOptimizationRuns(config, runs)

        assertEquals(3, aggregate.measuredRuns)
        assertEquals(1.0, aggregate.completionRate)
        assertEquals(0.5, aggregate.citationCorrectRate)
        assertEquals(0.5, aggregate.exactQuoteRate)
        assertEquals(1.0, aggregate.referenceAnswerCoverage)
        assertEquals(1.0, aggregate.abstentionCorrectness)
        assertEquals(2_000, aggregate.latencyP50Millis)
        assertEquals(3_000, aggregate.latencyP95Millis)
        assertTrue(aggregate.qualityScore in 0.8..1.0)
    }

    @Test
    fun `quality gate rejects a faster candidate with material citation regression`() {
        val baseline = aggregateOptimizationRuns(
            config("baseline"),
            listOf(run("baseline", "answerable", 1, true, 5_000, citations = true, quotes = true, coverage = true, format = true)),
        )
        val optimized = aggregateOptimizationRuns(
            config("optimized"),
            listOf(run("optimized", "answerable", 1, true, 500, citations = false, quotes = false, coverage = true, format = true)),
        )

        val decision = decideWinner(baseline, optimized)

        assertFalse(decision.optimizedAccepted)
        assertEquals("baseline", decision.winnerConfigurationId)
    }

    @Test
    fun `report store atomically writes parseable partial json and markdown`() {
        val root = Files.createTempDirectory("local-llm-optimization-test")
        val json = root.resolve("nested/optimization.json")
        val markdown = root.resolve("nested/optimization.md")
        val report = OptimizationReport(
            status = "completed",
            environment = HostEnvironment("2026-10-07T00:00:00Z", "macOS", "arm64", memoryBytes = 32_000_000_000, javaVersion = "21", installedModels = listOf(BASE_MODEL)),
            quantization = QuantizationFinding("Q4_K_M", "Q4_K_M", false, "no alternative"),
            baseline = config("baseline"),
            candidates = emptyList(),
            runs = emptyList(),
        )

        OptimizationReportStore(json, markdown).save(report)

        assertEquals("completed", Json.parseToJsonElement(Files.readString(json)).jsonObject["status"]?.jsonPrimitive?.content)
        assertTrue(Files.readString(markdown).contains("Local LLM optimization report"))
        assertTrue(Files.list(json.parent).use { paths -> paths.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    private fun config(id: String) = OptimizationConfiguration(
        id = id,
        model = BASE_MODEL,
        temperature = 0.2,
        maxTokens = 384,
        contextWindowTokens = OPTIMIZED_CONTEXT_WINDOW,
        promptVersion = RagPromptVersion.REPO_TECH_V2.wireId,
    )

    private fun run(
        configurationId: String,
        caseId: String,
        repetition: Int,
        success: Boolean,
        latency: Long,
        citations: Boolean,
        quotes: Boolean,
        coverage: Boolean,
        abstention: Boolean = false,
        format: Boolean,
    ) = OptimizationRun(
        phase = "test",
        configurationId = configurationId,
        caseId = caseId,
        repetition = repetition,
        success = success,
        answer = if (repetition == 1) "Ответ" else "Другой ответ",
        timeToFirstTokenMillis = latency / 10,
        latencyMillis = latency,
        promptTokens = 100,
        completionTokens = 20,
        totalTokens = 120,
        outputTokensPerSecond = 20.0,
        retrievedSources = listOf("README.md"),
        retrievedChunkIds = listOf("chunk-1"),
        completionValid = success,
        citationsValid = citations,
        quotesExact = quotes,
        referenceAnswerCovered = coverage,
        abstained = abstention,
        abstentionCorrect = if (caseId == ABSTENTION_CASE_ID) abstention else !abstention,
        formatValid = format,
    )
}
