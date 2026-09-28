package org.example.indexing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

enum class RequestedStrategies { FIXED, STRUCTURED, BOTH }

data class IndexingOptions(
    val corpusRoot: Path,
    val outputDirectory: Path,
    val strategies: RequestedStrategies = RequestedStrategies.BOTH,
    val fixedChunkSize: Int = DEFAULT_FIXED_CHUNK_SIZE,
    val fixedOverlap: Int = DEFAULT_FIXED_OVERLAP,
    val batchSize: Int = DEFAULT_EMBEDDING_BATCH_SIZE,
    val evaluationQueries: List<EvaluationQuery> = DEFAULT_EVALUATION_QUERIES,
    val productionEmbeddings: Boolean = true,
)

data class IndexingResult(
    val corpus: CorpusManifest,
    val indexes: Map<ChunkingKind, DocumentIndex>,
    val report: ChunkingComparisonReport,
    val indexFiles: Map<ChunkingKind, Path>,
    val comparisonJson: Path,
    val comparisonMarkdown: Path,
)

class DocumentIndexPipeline(
    private val embeddingClient: EmbeddingClient,
    private val collector: RepositoryCorpusCollector = RepositoryCorpusCollector(),
    private val knownSecrets: List<String> = emptyList(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun run(options: IndexingOptions): IndexingResult {
        require(options.batchSize > 0) { "Embedding batch size должен быть положительным" }
        require(options.evaluationQueries.size in 5..8) { "Evaluation-набор должен содержать от 5 до 8 запросов" }

        val collectionStarted = nanoTime()
        val corpus = collector.collect(options.corpusRoot)
        rejectSecrets(corpus.documents)
        val collectionMillis = elapsedMillis(collectionStarted)

        val strategies = when (options.strategies) {
            RequestedStrategies.FIXED -> listOf(FixedSizeChunkingStrategy(options.fixedChunkSize, options.fixedOverlap))
            RequestedStrategies.STRUCTURED -> listOf(StructureAwareChunkingStrategy())
            RequestedStrategies.BOTH -> listOf(
                FixedSizeChunkingStrategy(options.fixedChunkSize, options.fixedOverlap),
                StructureAwareChunkingStrategy(),
            )
        }

        val built = mutableListOf<BuiltStrategy>()
        for (strategy in strategies) {
            currentCoroutineContext().ensureActive()
            built += buildIndex(strategy, corpus, options, collectionMillis)
        }
        require(built.map { it.index.corpus.manifestHash }.distinct().size == 1) {
            "Стратегии должны использовать один corpus manifest"
        }

        val (queryVectors, evaluationBatchCalls) = embedBatches(
            options.evaluationQueries.map(EvaluationQuery::query),
            options.batchSize,
        )
        val compared = built.map { item ->
            val evaluationStarted = nanoTime()
            val evaluation = evaluate(item.index, options.evaluationQueries, queryVectors, options.productionEmbeddings)
            val evaluationMillis = elapsedMillis(evaluationStarted)
            item.toComparison(evaluation, evaluationMillis)
        }
        val dimensions = built.map { it.index.embedding.dimensions }.distinct().single()
        val descriptor = EmbeddingDescriptor(embeddingClient.provider, embeddingClient.model, dimensions)
        val report = ChunkingComparisonReport(
            corpus = corpus.manifest,
            embedding = descriptor,
            evaluationEmbeddingBatchCalls = evaluationBatchCalls,
            evaluationUsesProductionEmbeddings = options.productionEmbeddings,
            strategies = compared,
        )
        val comparisonJson = options.outputDirectory.resolve("comparison.json")
        val comparisonMarkdown = options.outputDirectory.resolve("comparison.md")
        JsonComparisonReportStore(comparisonJson, comparisonMarkdown).save(report)

        return IndexingResult(
            corpus = corpus.manifest,
            indexes = built.associate { it.index.strategy to it.index },
            report = report,
            indexFiles = built.associate { it.index.strategy to it.file },
            comparisonJson = comparisonJson,
            comparisonMarkdown = comparisonMarkdown,
        )
    }

    private suspend fun buildIndex(
        strategy: ChunkingStrategy,
        corpus: CollectedCorpus,
        options: IndexingOptions,
        collectionMillis: Long,
    ): BuiltStrategy {
        val chunkingStarted = nanoTime()
        val drafts = strategy.chunk(corpus.documents)
        val chunkingMillis = elapsedMillis(chunkingStarted)
        require(drafts.isNotEmpty() && drafts.none { it.text.isBlank() }) { "${strategy.kind.wireName} не создал непустые chunks" }
        require(drafts.map(ChunkDraft::chunkId).distinct().size == drafts.size) { "Chunk IDs должны быть уникальны" }

        val (vectors, batchCalls) = embedBatches(drafts.map(ChunkDraft::text), options.batchSize)
        val dimensions = vectors.first().values.size
        require(vectors.all { it.values.size == dimensions }) { "Embedding dimensions отличаются между batch-вызовами" }
        val chunks = drafts.zip(vectors).map { (draft, vector) ->
            IndexedChunk(
                chunkId = draft.chunkId,
                text = draft.text,
                embedding = vector.values,
                source = draft.source,
                title = draft.title,
                section = draft.section,
                strategy = draft.strategy,
                ordinal = draft.ordinal,
                startOffset = draft.startOffset,
                endOffset = draft.endOffset,
                documentContentHash = draft.documentContentHash,
                metadata = ChunkMetadata(draft.source, draft.title, draft.section, draft.chunkId),
            )
        }
        val index = DocumentIndex(
            strategy = strategy.kind,
            parameters = strategy.parameters,
            embedding = EmbeddingDescriptor(embeddingClient.provider, embeddingClient.model, dimensions),
            corpus = corpus.manifest,
            chunks = chunks,
        )
        val file = options.outputDirectory.resolve("${strategy.kind.wireName}.json")
        val serializationStarted = nanoTime()
        JsonDocumentIndexStore(file).save(index)
        val serializationMillis = elapsedMillis(serializationStarted)
        return BuiltStrategy(
            index = index,
            file = file,
            fileBytes = Files.size(file),
            embeddingBatchCalls = batchCalls,
            collectionMillis = collectionMillis,
            chunkingMillis = chunkingMillis,
            serializationMillis = serializationMillis,
        )
    }

    private suspend fun embedBatches(texts: List<String>, batchSize: Int): Pair<List<EmbeddingVector>, Int> {
        val result = ArrayList<EmbeddingVector>(texts.size)
        var calls = 0
        texts.chunked(batchSize).forEach { batch ->
            currentCoroutineContext().ensureActive()
            val vectors = embeddingClient.embed(batch)
            require(vectors.size == batch.size) { "Embedding client вернул неверное число vectors" }
            result += vectors
            calls++
        }
        require(result.isNotEmpty()) { "Нет текстов для embedding" }
        val dimensions = result.first().values.size
        require(dimensions > 0 && result.all { it.values.size == dimensions && it.values.all(Float::isFinite) }) {
            "Embedding vectors должны иметь одинаковую положительную размерность и конечные значения"
        }
        return result to calls
    }

    private fun rejectSecrets(documents: List<NormalizedDocument>) {
        val configuredSecrets = knownSecrets.filter { it.isNotBlank() }
        require(documents.none { document -> configuredSecrets.any(document.text::contains) }) {
            "Корпус содержит настроенный API-ключ; индекс не создан"
        }
        require(documents.none { GENERIC_OPENAI_KEY.containsMatchIn(it.text) }) {
            "Корпус содержит строку, похожую на OpenAI API-ключ; индекс не создан"
        }
    }

    private fun elapsedMillis(started: Long): Long = ((nanoTime() - started).coerceAtLeast(0L)) / 1_000_000

    private data class BuiltStrategy(
        val index: DocumentIndex,
        val file: Path,
        val fileBytes: Long,
        val embeddingBatchCalls: Int,
        val collectionMillis: Long,
        val chunkingMillis: Long,
        val serializationMillis: Long,
    ) {
        fun toComparison(evaluation: EvaluationMetrics, evaluationMillis: Long): StrategyComparison {
            val sizes = index.chunks.map { it.text.length }.sorted()
            val target = index.parameters.targetSize
            return StrategyComparison(
                strategy = index.strategy,
                documentCount = index.corpus.stats.documentCount,
                chunkCount = index.chunks.size,
                chunkSizes = ChunkSizeStats(
                    minimum = sizes.first(),
                    average = sizes.average(),
                    median = median(sizes),
                    p95 = percentile(sizes, 0.95),
                    maximum = sizes.last(),
                ),
                totalCharacters = sizes.sumOf(Int::toLong),
                approximateTokens = approximateTokens(sizes.sumOf(Int::toLong)),
                chunksWithSectionPercent = index.chunks.count { it.section.isNotBlank() } * 100.0 / index.chunks.size,
                emptyChunkCount = index.chunks.count { it.text.isBlank() },
                oversizedChunkCount = index.chunks.count { it.text.length > target },
                embeddingBatchCalls = embeddingBatchCalls,
                vectorDimensions = index.embedding.dimensions,
                indexFileBytes = fileBytes,
                localDurations = LocalStageDurations(collectionMillis, chunkingMillis, serializationMillis, evaluationMillis),
                evaluation = evaluation,
                advantages = if (index.strategy == ChunkingKind.FIXED) {
                    "Предсказуемый размер, простой контроль overlap и равномерные embedding batches."
                } else {
                    "Сохраняет heading path и границы крупных деклараций, поэтому provenance понятнее."
                },
                tradeoffs = if (index.strategy == ChunkingKind.FIXED) {
                    "Границы могут пересекать логические разделы, section намеренно пуст."
                } else {
                    "Размеры менее равномерны и зависят от качества структуры исходного файла."
                },
            )
        }
    }

    companion object {
        private val GENERIC_OPENAI_KEY = Regex("sk-[A-Za-z0-9_-]{20,}")

        private fun percentile(sorted: List<Int>, percentile: Double): Int {
            val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }

        private fun median(sorted: List<Int>): Double {
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle].toDouble()
            else (sorted[middle - 1].toLong() + sorted[middle].toLong()) / 2.0
        }
    }
}
