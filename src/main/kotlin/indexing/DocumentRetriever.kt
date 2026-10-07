package org.example.indexing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

const val DEFAULT_RAG_RETRIEVAL_LIMIT = 5
const val DOCUMENT_INDEX_BUILD_COMMAND =
    "./gradlew buildLocalDocumentIndex"

data class RetrievedDocumentChunk(
    val rank: Int,
    val score: Double,
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val text: String,
)

data class DocumentRetrievalResult(
    val strategy: ChunkingKind,
    val embeddingModel: String,
    val manifestHash: String,
    val chunks: List<RetrievedDocumentChunk>,
    val embeddingProvider: String = "unknown",
    val queryEmbeddingElapsedMillis: Long = 0,
    val searchElapsedMillis: Long = 0,
    val retrievalElapsedMillis: Long = 0,
)

class DocumentRetrievalException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Production retrieval boundary shared by the web RAG mode and evaluation CLI. */
class DocumentRetriever(
    private val indexStore: JsonDocumentIndexStore,
    private val nanoTime: () -> Long = System::nanoTime,
    private val embeddingClientFactory: (EmbeddingDescriptor) -> EmbeddingClient,
) {
    suspend fun retrieve(question: String, limit: Int = DEFAULT_RAG_RETRIEVAL_LIMIT): DocumentRetrievalResult {
        require(question.isNotBlank()) { "Вопрос для RAG не может быть пустым" }
        require(limit > 0) { "Retrieval limit должен быть положительным" }
        val retrievalStarted = nanoTime()
        currentCoroutineContext().ensureActive()

        val index = try {
            indexStore.load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw unavailableIndex(error)
        }
        if (index.strategy != ChunkingKind.STRUCTURED) {
            throw DocumentRetrievalException(
                "RAG требует structure-aware индекс. Постройте его командой: $DOCUMENT_INDEX_BUILD_COMMAND",
            )
        }
        currentCoroutineContext().ensureActive()

        val embeddingClient = try {
            embeddingClientFactory(index.embedding)
        } catch (error: CancellationException) {
            throw error
        } catch (error: DocumentRetrievalException) {
            throw error
        } catch (error: Exception) {
            throw DocumentRetrievalException(
                "Embedding client несовместим с индексом ${index.embedding.provider}/${index.embedding.model}. " +
                    "Перестройте индекс командой: $DOCUMENT_INDEX_BUILD_COMMAND",
                error,
            )
        }
        if (embeddingClient.provider != index.embedding.provider || embeddingClient.model != index.embedding.model) {
            throw DocumentRetrievalException(
                "Индекс создан для ${index.embedding.provider}/${index.embedding.model}, а доступен " +
                    "${embeddingClient.provider}/${embeddingClient.model}. Перестройте индекс командой: $DOCUMENT_INDEX_BUILD_COMMAND",
            )
        }

        val embeddingStarted = nanoTime()
        val queryVector = try {
            embeddingClient.embed(listOf(question)).singleOrNull()
                ?: throw IllegalArgumentException("Embedding client вернул неверное число query vectors")
        } catch (error: CancellationException) {
            throw error
        } catch (error: DocumentRetrievalException) {
            throw error
        } catch (error: Exception) {
            throw DocumentRetrievalException("Не удалось создать embedding вопроса: ${safeReason(error)}", error)
        }
        val queryEmbeddingElapsedMillis = elapsedMillis(embeddingStarted)
        currentCoroutineContext().ensureActive()
        if (queryVector.values.size != index.embedding.dimensions) {
            throw DocumentRetrievalException(
                "Размерность embedding вопроса (${queryVector.values.size}) не совпадает с индексом " +
                    "(${index.embedding.dimensions}). Перестройте индекс командой: $DOCUMENT_INDEX_BUILD_COMMAND",
            )
        }

        // search() owns the common cosine implementation and deterministic score/chunkId ordering.
        yield()
        val searchStarted = nanoTime()
        val hits = try {
            search(index, queryVector, limit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw DocumentRetrievalException("Не удалось выполнить поиск по локальному индексу: ${safeReason(error)}", error)
        }
        val searchElapsedMillis = elapsedMillis(searchStarted)
        currentCoroutineContext().ensureActive()
        if (hits.isEmpty()) throw unavailableIndex(null)

        return DocumentRetrievalResult(
            strategy = index.strategy,
            embeddingProvider = index.embedding.provider,
            embeddingModel = index.embedding.model,
            manifestHash = index.corpus.manifestHash,
            queryEmbeddingElapsedMillis = queryEmbeddingElapsedMillis,
            searchElapsedMillis = searchElapsedMillis,
            retrievalElapsedMillis = elapsedMillis(retrievalStarted),
            chunks = hits.mapIndexed { indexOfHit, hit ->
                RetrievedDocumentChunk(
                    rank = indexOfHit + 1,
                    score = hit.score,
                    chunkId = hit.chunk.chunkId,
                    source = hit.chunk.source,
                    title = hit.chunk.title,
                    section = hit.chunk.section,
                    text = hit.chunk.text,
                )
            },
        )
    }

    private fun unavailableIndex(cause: Throwable?): DocumentRetrievalException = DocumentRetrievalException(
        "Локальный RAG-индекс отсутствует, повреждён или имеет неподдерживаемую версию. " +
            "Постройте его командой: $DOCUMENT_INDEX_BUILD_COMMAND",
        cause,
    )

    private fun safeReason(error: Throwable): String = error.message
        ?.filter { it == '\n' || it == '\t' || !it.isISOControl() }
        ?.take(500)
        ?.takeIf(String::isNotBlank)
        ?: "неизвестная ошибка"

    private fun elapsedMillis(started: Long): Long = (nanoTime() - started).coerceAtLeast(0L) / 1_000_000
}
