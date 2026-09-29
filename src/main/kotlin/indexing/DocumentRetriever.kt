package org.example.indexing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

const val DEFAULT_RAG_RETRIEVAL_LIMIT = 5
const val DOCUMENT_INDEX_BUILD_COMMAND =
    "./gradlew buildDocumentIndexes --args=\"--root . --output .llm-document-index --strategy structured --embedding-model text-embedding-3-small --batch-size 64\""

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
)

class DocumentRetrievalException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Production retrieval boundary shared by the web RAG mode and evaluation CLI. */
class DocumentRetriever(
    private val indexStore: JsonDocumentIndexStore,
    private val embeddingClientFactory: (EmbeddingDescriptor) -> EmbeddingClient,
) {
    suspend fun retrieve(question: String, limit: Int = DEFAULT_RAG_RETRIEVAL_LIMIT): DocumentRetrievalResult {
        require(question.isNotBlank()) { "Вопрос для RAG не может быть пустым" }
        require(limit > 0) { "Retrieval limit должен быть положительным" }
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
        if (index.chunks.size < limit) {
            throw DocumentRetrievalException(
                "RAG-индекс содержит ${index.chunks.size} chunks и не может вернуть top-$limit. " +
                    "Перестройте индекс командой: $DOCUMENT_INDEX_BUILD_COMMAND",
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
        currentCoroutineContext().ensureActive()
        if (queryVector.values.size != index.embedding.dimensions) {
            throw DocumentRetrievalException(
                "Размерность embedding вопроса (${queryVector.values.size}) не совпадает с индексом " +
                    "(${index.embedding.dimensions}). Перестройте индекс командой: $DOCUMENT_INDEX_BUILD_COMMAND",
            )
        }

        // search() owns the common cosine implementation and deterministic score/chunkId ordering.
        yield()
        val hits = try {
            search(index, queryVector, limit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw DocumentRetrievalException("Не удалось выполнить поиск по локальному индексу: ${safeReason(error)}", error)
        }
        currentCoroutineContext().ensureActive()
        if (hits.isEmpty()) throw unavailableIndex(null)

        return DocumentRetrievalResult(
            strategy = index.strategy,
            embeddingModel = index.embedding.model,
            manifestHash = index.corpus.manifestHash,
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
}
