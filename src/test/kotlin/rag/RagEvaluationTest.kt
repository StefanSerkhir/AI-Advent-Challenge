package org.example.rag

import kotlinx.coroutines.runBlocking
import org.example.app.RagComparisonRunner
import org.example.indexing.DeterministicFakeEmbeddingClient
import org.example.indexing.DocumentRetriever
import org.example.indexing.JsonDocumentIndexStore
import org.example.indexing.createRagFixtureIndex
import org.example.llm.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagEvaluationTest {
    @Test
    fun `evaluation runner records all ten cases and versioned JSON markdown report`() = runBlocking {
        val directory = Files.createTempDirectory("rag-evaluation")
        try {
            val indexFile = createRagFixtureIndex(directory)
            val client = object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    val system = messages.firstOrNull { it.role == LlmRole.SYSTEM }?.content.orEmpty()
                    if ("Перепиши исходный вопрос" in system) {
                        return CompletionResult(
                            content = messages.last().content + " документация",
                            finishReason = "stop",
                            usage = TokenUsage(8, 3, 11),
                            model = "fixture-model",
                        )
                    }
                    val rag = "предоставленный контекст" in system
                    return CompletionResult(
                        content = if (rag) "Grounded fixture answer [S1]." else "Baseline fixture answer.",
                        finishReason = "stop",
                        usage = TokenUsage(12, 4, 16),
                        model = "fixture-model",
                    )
                }
            }
            val comparison = RagComparisonRunner(
                clientProvider = { client },
                retrieverProvider = {
                    DocumentRetriever(JsonDocumentIndexStore(indexFile)) { descriptor ->
                        DeterministicFakeEmbeddingClient(descriptor.dimensions, descriptor.model)
                    }
                },
            )
            val report = RagEvaluationRunner(comparison, ragMinSimilarity = -1.0).run("fixture-model", 100)
            assertEquals(10, report.cases.size)
            assertTrue(report.cases.all { it.baseline.content != null && it.raw.answer.content != null && it.enhanced.answer.content != null })
            assertTrue(report.cases.all { it.raw.retrievedSources.size == 5 && it.raw.citationsValid })
            assertTrue(report.cases.all { it.enhanced.retrievedSources.size == 5 && it.enhanced.citationsValid })
            assertTrue(report.cases.all { it.enhanced.rewrite?.totalTokens == 11 })
            assertTrue(report.cases.all { it.raw.manualAssessment.correctness == null && it.enhanced.manualAssessment.correctness == null })

            val json = directory.resolve("comparison.json")
            val markdown = directory.resolve("comparison.md")
            val store = RagEvaluationReportStore(json, markdown)
            store.save(report)
            assertEquals(report, store.load())
            assertContains(Files.readString(markdown), "Ручная оценка 0–2")
            assertContains(Files.readString(markdown), "Raw RAG")
            assertContains(Files.readString(markdown), "Enhanced RAG")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `report store migrates format v1 and round trips v2`() {
        val directory = Files.createTempDirectory("rag-evaluation-v1")
        try {
            val json = directory.resolve("comparison.json")
            val markdown = directory.resolve("comparison.md")
            Files.writeString(json, """
                {
                  "formatVersion": 1,
                  "model": "old-model",
                  "note": "old report",
                  "cases": [{
                    "id": "old",
                    "question": "question",
                    "expectation": "expectation",
                    "expectedSources": ["README.md"],
                    "baseline": {"content":"baseline","model":"old-model","elapsedMillis":1},
                    "rag": {"content":"rag [S1]","model":"old-model","elapsedMillis":2},
                    "retrievedSources": [{"rank":1,"score":0.9,"chunkId":"c1","source":"README.md","title":"README","section":"Intro"}],
                    "expectedSourceFound": true,
                    "citationsValid": true
                  }]
                }
            """.trimIndent())
            val store = RagEvaluationReportStore(json, markdown)
            val migrated = store.load()
            assertEquals(2, migrated.formatVersion)
            assertEquals("rag [S1]", migrated.cases.single().raw.answer.content)
            assertContains(migrated.cases.single().enhanced.answer.error.orEmpty(), "v1")

            store.save(migrated)
            assertEquals(migrated, store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
