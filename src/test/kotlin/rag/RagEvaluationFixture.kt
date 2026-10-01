package org.example.rag

import kotlinx.coroutines.runBlocking
import org.example.app.RagComparisonRunner
import org.example.indexing.*
import org.example.llm.*
import java.nio.file.Files
import java.nio.file.Path

fun main() = runBlocking {
    val temporary = Files.createTempDirectory("rag-evaluation-fixture-index")
    try {
        val indexFile = createRagFixtureIndex(temporary)
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                if (messages.first().content.contains("Перепиши исходный вопрос")) {
                    return CompletionResult(
                        content = messages.last().content + " локальная техническая документация",
                        finishReason = "stop",
                        usage = TokenUsage(18, 7, 25),
                        model = "fixture-generation-v1",
                    )
                }
                val rag = messages.first().role == LlmRole.SYSTEM
                val question = Regex("Вопрос:\\s*([\\s\\S]*?)\\s*Контекст:")
                    .find(messages.last().content)?.groupValues?.get(1)?.trim()
                    ?: messages.last().content
                return CompletionResult(
                    content = if (rag) groundedRagFixtureAnswer(
                        messages.last().content,
                        "Fixture RAG-ответ на вопрос «$question» использует локальный контекст [S1].",
                    ) else
                        "Fixture baseline-ответ на вопрос «${messages.last().content}».",
                    finishReason = "stop",
                    usage = TokenUsage(40, 20, 60),
                    model = "fixture-generation-v1",
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
        val report = RagEvaluationRunner(comparison).run("fixture-generation-v1", 300)
        val output = Path.of(".llm-rag-evaluation")
        RagEvaluationReportStore(output.resolve("comparison.json"), output.resolve("comparison.md")).save(report)
        println("SAFE FIXTURE: 10 cases; deterministic fake embeddings/generation; external API calls: 0")
        println("Report: ${output.resolve("comparison.json")} and ${output.resolve("comparison.md")}")
        println("This report verifies mechanics only, not production RAG quality.")
    } finally {
        temporary.toFile().deleteRecursively()
    }
}
