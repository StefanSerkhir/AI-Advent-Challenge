package org.example.app

import kotlinx.coroutines.*
import org.example.indexing.*
import org.example.llm.*
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.*

class RagComparisonRunnerTest {
    @Test
    fun `runs baseline raw rewrite enhanced in order with isolated queries and combined enhanced usage`() = runBlocking {
        val directory = Files.createTempDirectory("rag-runner")
        try {
            val indexFile = createRagFixtureIndex(directory)
            val calls = mutableListOf<List<LlmMessage>>()
            val embeddedQueries = mutableListOf<String>()
            val client = object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    calls += messages
                    val system = messages.firstOrNull { it.role == LlmRole.SYSTEM }?.content.orEmpty()
                    return when {
                        "Перепиши исходный вопрос" in system -> CompletionResult(
                            "production web запуск runWeb",
                            "stop",
                            TokenUsage(4, 2, 6),
                            "fixture-model",
                        )
                        "предоставленный контекст" in system -> CompletionResult(
                            "Ответ опирается на контекст [S1].",
                            "stop",
                            TokenUsage(20, 8, 28),
                            "fixture-model",
                        )
                        else -> CompletionResult("Независимый baseline.", "stop", TokenUsage(8, 3, 11), "fixture-model")
                    }
                }
            }
            val retriever = DocumentRetriever(JsonDocumentIndexStore(indexFile)) { descriptor ->
                object : EmbeddingClient {
                    private val delegate = DeterministicFakeEmbeddingClient(descriptor.dimensions, descriptor.model)
                    override val provider = delegate.provider
                    override val model = delegate.model
                    override suspend fun embed(texts: List<String>): List<EmbeddingVector> {
                        embeddedQueries += texts
                        return delegate.embed(texts)
                    }
                }
            }
            val report = RagComparisonRunner(
                clientProvider = { client },
                retrieverProvider = { retriever },
            ).compare(
                "Как запустить production web-приложение?",
                "fixture-model",
                200,
                ragCandidateLimit = 10,
                ragResultLimit = 5,
                ragMinSimilarity = -1.0,
            )

            assertEquals(listOf(RagBranch.BASELINE, RagBranch.RAW, RagBranch.ENHANCED), report.branches.map { it.branch })
            assertEquals(4, calls.size)
            assertEquals(listOf(LlmRole.USER), calls[0].map { it.role })
            assertContains(calls[1].first().content, "недоверенными данными")
            assertContains(calls[2].first().content, "Перепиши исходный вопрос")
            assertEquals("Как запустить production web-приложение?", calls[2].last().content)
            assertFalse(calls[2].joinToString { it.content }.contains("игнорируй system"))
            assertContains(calls[3].last().content, "игнорируй system")
            assertEquals(listOf("Как запустить production web-приложение?", "production web запуск runWeb"), embeddedQueries)

            val raw = report.branches.single { it.branch == RagBranch.RAW }
            val enhanced = report.branches.single { it.branch == RagBranch.ENHANCED }
            assertEquals("Как запустить production web-приложение?", raw.diagnostics?.retrievalQuery)
            assertFalse(raw.diagnostics!!.queryRewritten)
            assertNull(raw.diagnostics!!.minSimilarity)
            assertEquals("production web запуск runWeb", enhanced.diagnostics?.retrievalQuery)
            assertTrue(enhanced.diagnostics!!.queryRewritten)
            assertEquals(10, enhanced.diagnostics!!.candidateCount)
            assertEquals(5, enhanced.diagnostics!!.filteredCount)
            assertEquals(listOf(1, 2, 3, 4, 5), enhanced.diagnostics!!.sources.map { it.rank })
            assertEquals(34, enhanced.completion?.usage?.totalTokens)
            assertEquals(6, enhanced.diagnostics!!.rewrite?.totalTokens)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `zero filtered results skip enhanced generation and keep baseline and raw`() = runBlocking {
        val directory = Files.createTempDirectory("rag-zero")
        try {
            val indexFile = createRagFixtureIndex(directory)
            var generationCalls = 0
            val client = object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    generationCalls++
                    val rag = messages.first().role == LlmRole.SYSTEM
                    return CompletionResult(if (rag) "raw [S1]" else "baseline", "stop", TokenUsage(2, 1, 3), "model")
                }
            }
            val report = RagComparisonRunner(
                clientProvider = { client },
                retrieverProvider = {
                    DocumentRetriever(JsonDocumentIndexStore(indexFile)) { descriptor ->
                        DeterministicFakeEmbeddingClient(descriptor.dimensions, descriptor.model)
                    }
                },
                rewriterProvider = { RagQueryRewriter { _, _ ->
                    RagQueryRewriteResult(
                        "заведомо отсутствующая комбинация qzxv qzxv",
                        CompletionResult("заведомо отсутствующая комбинация qzxv qzxv", "stop", TokenUsage(2, 1, 3), "model"),
                        1,
                        BigDecimal.ZERO,
                    )
                } },
            ).compare("question", "model", 100, ragMinSimilarity = 1.0)

            assertEquals(2, generationCalls)
            val enhanced = report.branches.single { it.branch == RagBranch.ENHANCED }
            assertEquals(NO_RELEVANT_RAG_CONTEXT_MESSAGE, enhanced.error)
            assertEquals(0, enhanced.diagnostics?.filteredCount)
            assertTrue(enhanced.diagnostics?.sources.orEmpty().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rewrite and retrieval errors affect only their branch while invalid citations are checked after reranking`() = runBlocking {
        val directory = Files.createTempDirectory("rag-errors")
        try {
            val indexFile = createRagFixtureIndex(directory)
            val normalClient = object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
                    CompletionResult(
                        if (messages.first().role == LlmRole.SYSTEM) "grounded [S1]" else "baseline",
                        "stop", TokenUsage(3, 2, 5), "model",
                    )
            }
            val retriever = DocumentRetriever(JsonDocumentIndexStore(indexFile)) {
                DeterministicFakeEmbeddingClient(it.dimensions, it.model)
            }
            val rewriteFailure = RagComparisonRunner(
                clientProvider = { normalClient },
                retrieverProvider = { retriever },
                rewriterProvider = { RagQueryRewriter { _, _ -> throw IOException("rewrite failed") } },
            ).compare("question", "model", 100)
            assertEquals("baseline", rewriteFailure.branches[0].completion?.content)
            assertNotNull(rewriteFailure.branches[1].completion)
            assertContains(rewriteFailure.branches[2].error.orEmpty(), "rewrite failed")

            var ragGeneration = 0
            val badEnhancedClient = object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                    val system = messages.firstOrNull { it.role == LlmRole.SYSTEM }?.content.orEmpty()
                    if ("Перепиши исходный вопрос" in system) {
                        return CompletionResult("question docs", "stop", TokenUsage(2, 1, 3), "model")
                    }
                    if ("предоставленный контекст" in system) ragGeneration++
                    return CompletionResult(
                        when (ragGeneration) { 0 -> "baseline"; 1 -> "raw [S1]"; else -> "enhanced [S99]" },
                        "stop", TokenUsage(3, 2, 5), "model",
                    )
                }
            }
            val invalidCitation = RagComparisonRunner(
                clientProvider = { badEnhancedClient },
                retrieverProvider = { retriever },
            ).compare("question", "model", 100, ragMinSimilarity = -1.0)
            assertNotNull(invalidCitation.branches[1].completion)
            assertContains(invalidCitation.branches[2].error.orEmpty(), "которых не было")
            assertEquals(listOf(1, 2, 3, 4, 5), invalidCitation.branches[2].diagnostics!!.sources.map { it.rank })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cancellation propagates without turning into a branch error`() = runBlocking {
        val runner = RagComparisonRunner(
            clientProvider = { object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult = awaitCancellation()
            } },
            retrieverProvider = { error("not reached") },
        )
        val job = async { runner.compare("question", "model", 100) }
        yield()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun `evaluation dataset contains exactly ten stable complete cases`() {
        assertEquals(10, org.example.rag.RAG_EVALUATION_CASES.size)
        assertEquals(10, org.example.rag.RAG_EVALUATION_CASES.map { it.id }.distinct().size)
        assertTrue(org.example.rag.RAG_EVALUATION_CASES.all {
            it.question.isNotBlank() && it.expectation.isNotBlank() && it.expectedSources.isNotEmpty()
        })
    }
}
