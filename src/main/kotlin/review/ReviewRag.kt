package org.example.review

import org.example.indexing.DocumentRetrievalResult
import org.example.indexing.DocumentRetriever

const val DEFAULT_REVIEW_RAG_LIMIT_PER_DOMAIN = 5
const val DEFAULT_REVIEW_RAG_CONTEXT_LIMIT = 40_000
const val DEFAULT_REVIEW_QUERY_LIMIT = 12_000

fun interface ReviewRagProvider {
    suspend fun retrieve(query: String): List<ReviewRagSource>
}

class RepositoryReviewRagProvider(
    private val retriever: DocumentRetriever,
    private val limitPerDomain: Int = DEFAULT_REVIEW_RAG_LIMIT_PER_DOMAIN,
    private val contextCharacterLimit: Int = DEFAULT_REVIEW_RAG_CONTEXT_LIMIT,
) : ReviewRagProvider {
    init {
        require(limitPerDomain > 0)
        require(contextCharacterLimit > 0)
    }

    override suspend fun retrieve(query: String): List<ReviewRagSource> {
        val documentation = retriever.retrieveDocumentation(query, limitPerDomain)
        val code = retriever.retrieveProductionCode(query, limitPerDomain)
        val combined = documentation.toReviewSources(RagDomain.DOCUMENTATION, "D") +
            code.toReviewSources(RagDomain.CODE, "C")
        val seen = mutableSetOf<String>()
        val unique = combined.filter { seen.add(it.chunkId) }
        val bounded = mutableListOf<ReviewRagSource>()
        var characters = 0
        unique.forEach { source ->
            val cost = source.text.length + source.source.length + source.section.length + 100
            if (characters + cost <= contextCharacterLimit) {
                bounded += source
                characters += cost
            }
        }
        require(bounded.any { it.domain == RagDomain.DOCUMENTATION }) {
            "Review RAG не нашёл документационный контекст в установленном лимите"
        }
        require(bounded.any { it.domain == RagDomain.CODE }) {
            "Review RAG не нашёл production-code контекст в установленном лимите"
        }
        return bounded
    }

    private fun DocumentRetrievalResult.toReviewSources(domain: RagDomain, prefix: String): List<ReviewRagSource> =
        chunks.mapIndexed { index, chunk ->
            ReviewRagSource(
                id = "$prefix${index + 1}",
                domain = domain,
                source = chunk.source,
                section = chunk.section,
                chunkId = chunk.chunkId,
                rank = chunk.rank,
                score = chunk.score,
                text = chunk.text,
            )
        }
}

class ReviewRetrievalQueryBuilder(private val maximumCharacters: Int = DEFAULT_REVIEW_QUERY_LIMIT) {
    init {
        require(maximumCharacters > 0)
    }

    fun build(metadata: PullRequestMetadata, diff: PullRequestDiff): String {
        val identifiers = linkedSetOf<String>()
        diff.files.asSequence()
            .flatMap { file ->
                sequenceOf(file.path, file.previousPath.orEmpty(), file.patch)
            }
            .flatMap { text -> IDENTIFIER.findAll(text).map(MatchResult::value) }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .take(200)
            .forEach(identifiers::add)
        val changeSummary = diff.files.joinToString("\n") { file ->
            "${file.kind}: ${file.previousPath?.let { "$it -> " }.orEmpty()}${file.path}${if (file.binary) " (binary)" else ""}"
        }
        val addedText = diff.files.joinToString("\n") { file ->
            file.patch.lineSequence()
                .filter { it.startsWith('+') && !it.startsWith("+++") }
                .take(80)
                .joinToString("\n") { it.drop(1) }
        }
        return buildString {
            appendLine("Pull request: ${metadata.title.take(500)}")
            appendLine("Description: ${metadata.body.take(2_000)}")
            appendLine("Change types and paths:")
            appendLine(changeSummary)
            appendLine("Identifiers: ${identifiers.joinToString(", ")}")
            appendLine("Changed code excerpts:")
            append(addedText)
        }.take(maximumCharacters)
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_.:/-]{2,}")
        private val STOP_WORDS = setOf("diff", "git", "index", "class", "fun", "const", "return", "import")
    }
}
