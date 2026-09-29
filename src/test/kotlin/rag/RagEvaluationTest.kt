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
                    val rag = messages.first().role == LlmRole.SYSTEM
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
            val report = RagEvaluationRunner(comparison).run("fixture-model", 100)
            assertEquals(10, report.cases.size)
            assertTrue(report.cases.all { it.baseline.content != null && it.rag.content != null })
            assertTrue(report.cases.all { it.retrievedSources.size == 5 && it.citationsValid })
            assertTrue(report.cases.all { it.manualAssessment.correctness == null })

            val json = directory.resolve("comparison.json")
            val markdown = directory.resolve("comparison.md")
            val store = RagEvaluationReportStore(json, markdown)
            store.save(report)
            assertEquals(report, store.load())
            assertContains(Files.readString(markdown), "Ручная оценка 0–2")
            assertContains(Files.readString(markdown), "Top-5 sources")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
