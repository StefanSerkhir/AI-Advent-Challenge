package org.example.indexing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val DOCUMENT_INDEX_FORMAT_VERSION = 1
const val COMPARISON_FORMAT_VERSION = 1
const val DEFAULT_FIXED_CHUNK_SIZE = 1_200
const val DEFAULT_FIXED_OVERLAP = 200
const val DEFAULT_STRUCTURED_CHUNK_SIZE = 1_600
const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small"
const val DEFAULT_EMBEDDING_BATCH_SIZE = 64
const val DEFAULT_MINIMUM_CORPUS_PAGES = 20.0
const val APPROXIMATE_CHARACTERS_PER_PAGE = 1_800

@Serializable
enum class DocumentKind {
    @SerialName("markdown") MARKDOWN,
    @SerialName("pdf") PDF,
    @SerialName("kotlin") KOTLIN,
    @SerialName("typescript") TYPESCRIPT,
    @SerialName("tsx") TSX,
    @SerialName("text") TEXT,
}

@Serializable
enum class ChunkingKind(val wireName: String) {
    @SerialName("fixed") FIXED("fixed"),
    @SerialName("structured") STRUCTURED("structured"),
}

data class NormalizedDocument(
    val source: String,
    val title: String,
    val kind: DocumentKind,
    val text: String,
    val contentHash: String,
)

@Serializable
data class CorpusDocumentManifest(
    val source: String,
    val title: String,
    val kind: DocumentKind,
    val contentHash: String,
    val characterCount: Int,
    val wordCount: Int,
)

@Serializable
data class CorpusStats(
    val documentCount: Int,
    val characterCount: Long,
    val wordCount: Long,
    val approximatePages: Double,
    val pageEstimateFormula: String = "characterCount / $APPROXIMATE_CHARACTERS_PER_PAGE",
)

@Serializable
data class CorpusManifest(
    val rootLabel: String = ".",
    val documents: List<CorpusDocumentManifest>,
    val stats: CorpusStats,
    val manifestHash: String,
)

data class CollectedCorpus(
    val documents: List<NormalizedDocument>,
    val manifest: CorpusManifest,
)

data class ChunkDraft(
    val chunkId: String,
    val text: String,
    val source: String,
    val title: String,
    val section: String,
    val strategy: ChunkingKind,
    val ordinal: Int,
    val startOffset: Int,
    val endOffset: Int,
    val documentContentHash: String,
)

@Serializable
data class ChunkMetadata(
    val source: String,
    val title: String,
    val section: String,
    @SerialName("chunk_id") val chunkId: String,
)

@Serializable
data class IndexedChunk(
    val chunkId: String,
    val text: String,
    val embedding: List<Float>,
    val source: String,
    val title: String,
    val section: String,
    val strategy: ChunkingKind,
    val ordinal: Int,
    val startOffset: Int,
    val endOffset: Int,
    val documentContentHash: String,
    val metadata: ChunkMetadata,
)

@Serializable
data class ChunkingParameters(
    val targetSize: Int,
    val overlap: Int,
    val boundaryPolicy: String,
)

@Serializable
data class EmbeddingDescriptor(
    val provider: String,
    val model: String,
    val dimensions: Int,
)

@Serializable
data class DocumentIndex(
    val formatVersion: Int = DOCUMENT_INDEX_FORMAT_VERSION,
    val strategy: ChunkingKind,
    val parameters: ChunkingParameters,
    val embedding: EmbeddingDescriptor,
    val corpus: CorpusManifest,
    val chunks: List<IndexedChunk>,
)

@Serializable
data class ChunkSizeStats(
    val minimum: Int,
    val average: Double,
    val median: Double,
    val p95: Int,
    val maximum: Int,
)

@Serializable
data class EvaluationQuery(
    val id: String,
    val query: String,
    val expectedSources: List<String> = emptyList(),
    val expectedSectionContains: String? = null,
)

@Serializable
data class EvaluationQueryResult(
    val queryId: String,
    val expectedFoundRank: Int? = null,
    val topSources: List<String>,
)

@Serializable
data class EvaluationMetrics(
    val queryCount: Int,
    val hitAt3: Double,
    val meanReciprocalRank: Double,
    val expectedSourceFoundRate: Double,
    val results: List<EvaluationQueryResult>,
    val note: String,
)

@Serializable
data class LocalStageDurations(
    val collectionMillis: Long,
    val chunkingMillis: Long,
    val indexSerializationMillis: Long,
    val evaluationMillis: Long,
)

@Serializable
data class StrategyComparison(
    val strategy: ChunkingKind,
    val documentCount: Int,
    val chunkCount: Int,
    val chunkSizes: ChunkSizeStats,
    val totalCharacters: Long,
    val approximateTokens: Long,
    val chunksWithSectionPercent: Double,
    val emptyChunkCount: Int,
    val oversizedChunkCount: Int,
    val embeddingBatchCalls: Int,
    val vectorDimensions: Int,
    val indexFileBytes: Long,
    val localDurations: LocalStageDurations,
    val evaluation: EvaluationMetrics,
    val advantages: String,
    val tradeoffs: String,
)

@Serializable
data class ChunkingComparisonReport(
    val formatVersion: Int = COMPARISON_FORMAT_VERSION,
    val corpus: CorpusManifest,
    val embedding: EmbeddingDescriptor,
    val evaluationEmbeddingBatchCalls: Int,
    val evaluationUsesProductionEmbeddings: Boolean,
    val strategies: List<StrategyComparison>,
)
