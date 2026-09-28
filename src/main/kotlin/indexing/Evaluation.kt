package org.example.indexing

import kotlin.math.sqrt

data class SearchHit(val chunk: IndexedChunk, val score: Double)

fun cosineSimilarity(left: List<Float>, right: List<Float>): Double {
    require(left.size == right.size && left.isNotEmpty()) { "Vectors must have equal non-zero dimensions" }
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    left.indices.forEach { index ->
        val l = left[index].toDouble()
        val r = right[index].toDouble()
        require(l.isFinite() && r.isFinite()) { "Vectors must contain finite values" }
        dot += l * r
        leftNorm += l * l
        rightNorm += r * r
    }
    if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
    return dot / (sqrt(leftNorm) * sqrt(rightNorm))
}

fun search(index: DocumentIndex, queryVector: EmbeddingVector, limit: Int = 3): List<SearchHit> {
    require(queryVector.values.size == index.embedding.dimensions) { "Query vector dimensions do not match index" }
    require(limit > 0)
    return index.chunks.asSequence()
        .map { SearchHit(it, cosineSimilarity(queryVector.values, it.embedding)) }
        .sortedWith(compareByDescending<SearchHit>(SearchHit::score).thenBy { it.chunk.chunkId })
        .take(limit)
        .toList()
}

fun evaluate(
    index: DocumentIndex,
    queries: List<EvaluationQuery>,
    queryVectors: List<EmbeddingVector>,
    productionEmbeddings: Boolean,
): EvaluationMetrics {
    require(queries.isNotEmpty()) { "Evaluation set cannot be empty" }
    require(queries.size == queryVectors.size) { "Every evaluation query must have an embedding" }
    val results = queries.zip(queryVectors).map { (query, vector) ->
        val ranked = search(index, vector, index.chunks.size)
        val rank = ranked.indexOfFirst { hit -> query.matches(hit.chunk) }.takeIf { it >= 0 }?.plus(1)
        EvaluationQueryResult(query.id, rank, ranked.take(3).map { it.chunk.source })
    }
    return EvaluationMetrics(
        queryCount = queries.size,
        hitAt3 = results.count { (it.expectedFoundRank ?: Int.MAX_VALUE) <= 3 }.toDouble() / results.size,
        meanReciprocalRank = results.map { it.expectedFoundRank?.let { rank -> 1.0 / rank } ?: 0.0 }.average(),
        expectedSourceFoundRate = results.count { it.expectedFoundRank != null }.toDouble() / results.size,
        results = results,
        note = if (productionEmbeddings) {
            "Метрики рассчитаны теми же production embeddings, что и индекс."
        } else {
            "Метрики рассчитаны deterministic fake embeddings и проверяют воспроизводимость пайплайна, а не качество модели."
        },
    )
}

private fun EvaluationQuery.matches(chunk: IndexedChunk): Boolean =
    (expectedSources.isNotEmpty() && chunk.source in expectedSources) ||
        (expectedSectionContains?.let { chunk.section.contains(it, ignoreCase = true) } == true)

val DEFAULT_EVALUATION_QUERIES = listOf(
    EvaluationQuery("run-web", "Как запустить локальное web-приложение?", listOf("README.md")),
    EvaluationQuery("http-timeouts", "Где настроены таймауты и retry HTTP-клиента?", listOf("src/main/kotlin/network/HttpClientFactory.kt")),
    EvaluationQuery("openai-stream", "Как OpenAI transport обрабатывает streaming completion?", listOf("src/main/kotlin/llm/openai/OpenAiCompatibleLlmClient.kt")),
    EvaluationQuery("web-routes", "Где Ktor регистрирует REST и SSE маршруты?", listOf("src/main/kotlin/web/WebServer.kt")),
    EvaluationQuery("memory-layers", "Как устроены SHORT_TERM WORKING и LONG_TERM?", listOf("docs/ARCHITECTURE.md", "README.md"), "Слои памяти"),
    EvaluationQuery("local-config", "Как читается и атомарно сохраняется локальная конфигурация?", listOf("src/main/kotlin/config/LocalConfig.kt")),
)

internal fun ChunkingComparisonReport.toMarkdown(): String = buildString {
    appendLine("# Сравнение стратегий индексации")
    appendLine()
    appendLine("Корпус: ${corpus.stats.documentCount} документов, ${corpus.stats.characterCount} символов, " +
        "${corpus.stats.wordCount} слов, ${rootFormat("%.2f", corpus.stats.approximatePages)} стр.")
    appendLine("Оценка страниц: `${corpus.stats.pageEstimateFormula}`.")
    appendLine("Embedding: `${embedding.provider}/${embedding.model}`, ${embedding.dimensions} измерений.")
    appendLine()
    appendLine("| Стратегия | Документы | Чанки | min / avg / median / p95 / max | Символы | ~токены | С секцией | Пустые | Oversize | Batch calls | Размер файла | Hit@3 | MRR | Expected source |")
    appendLine("| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    strategies.forEach { item ->
        val sizes = item.chunkSizes
        appendLine("| ${item.strategy.wireName} | ${item.documentCount} | ${item.chunkCount} | " +
            "${sizes.minimum} / ${rootFormat("%.1f", sizes.average)} / ${rootFormat("%.1f", sizes.median)} / ${sizes.p95} / ${sizes.maximum} | " +
            "${item.totalCharacters} | ${item.approximateTokens} | ${rootFormat("%.1f", item.chunksWithSectionPercent)}% | " +
            "${item.emptyChunkCount} | ${item.oversizedChunkCount} | ${item.embeddingBatchCalls} | ${item.indexFileBytes} B | " +
            "${rootFormat("%.3f", item.evaluation.hitAt3)} | ${rootFormat("%.3f", item.evaluation.meanReciprocalRank)} | " +
            "${rootFormat("%.3f", item.evaluation.expectedSourceFoundRate)} |")
    }
    appendLine()
    appendLine("## Локальные этапы")
    appendLine()
    appendLine("Время embedding API намеренно не включено в локальные длительности.")
    strategies.forEach { item ->
        with(item.localDurations) {
            appendLine("- `${item.strategy.wireName}`: collection ${collectionMillis} ms; chunking ${chunkingMillis} ms; " +
                "serialization ${indexSerializationMillis} ms; evaluation ${evaluationMillis} ms.")
        }
    }
    appendLine()
    appendLine("## Выводы")
    appendLine()
    strategies.forEach { item ->
        appendLine("- `${item.strategy.wireName}`: ${item.advantages} Ограничения: ${item.tradeoffs}")
    }
    appendLine()
    appendLine(strategies.first().evaluation.note)
}
