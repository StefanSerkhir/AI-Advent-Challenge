package org.example.indexing

import kotlinx.coroutines.runBlocking
import org.example.config.LocalConfigStore
import org.example.network.createHttpClient
import java.nio.file.Path
import kotlin.io.path.absolute

fun main(args: Array<String>) = runBlocking {
    val arguments = IndexingCliArguments.parse(args)
    val root = arguments.root.absolute().normalize()
    val output = if (arguments.output.isAbsolute) arguments.output.normalize() else root.resolve(arguments.output).normalize()
    val openAiKey = LocalConfigStore(root.resolve(".env")).load().openAiApiKey
        ?.takeIf(String::isNotBlank)
        ?: error("В существующей конфигурации не задан openai_api_key. Индексация не запущена.")
    val httpClient = createHttpClient(maxRetries = 0)
    try {
        val result = DocumentIndexPipeline(
            embeddingClient = OpenAiEmbeddingClient(openAiKey, httpClient, arguments.embeddingModel),
            knownSecrets = listOf(openAiKey),
        ).run(
            IndexingOptions(
                corpusRoot = root,
                outputDirectory = output,
                strategies = arguments.strategies,
                fixedChunkSize = arguments.fixedChunkSize,
                fixedOverlap = arguments.overlap,
                batchSize = arguments.batchSize,
                productionEmbeddings = true,
            ),
        )
        val stats = result.corpus.stats
        println("Корпус: ${stats.documentCount} документов, ${stats.characterCount} символов, ${stats.wordCount} слов")
        println("Приблизительный объём: ${rootFormat("%.2f", stats.approximatePages)} страниц (${stats.pageEstimateFormula})")
        result.indexes.toSortedMap(compareBy(ChunkingKind::wireName)).forEach { (strategy, index) ->
            println("${strategy.wireName}: ${index.chunks.size} chunks -> ${result.indexFiles.getValue(strategy)}")
        }
        println("Сравнение: ${result.comparisonJson} и ${result.comparisonMarkdown}")
    } finally {
        httpClient.close()
    }
}

data class IndexingCliArguments(
    val root: Path = Path.of("."),
    val output: Path = Path.of(".llm-document-index"),
    val strategies: RequestedStrategies = RequestedStrategies.BOTH,
    val fixedChunkSize: Int = DEFAULT_FIXED_CHUNK_SIZE,
    val overlap: Int = DEFAULT_FIXED_OVERLAP,
    val embeddingModel: String = DEFAULT_EMBEDDING_MODEL,
    val batchSize: Int = DEFAULT_EMBEDDING_BATCH_SIZE,
) {
    companion object {
        fun parse(args: Array<String>): IndexingCliArguments {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val argument = args[index]
                require(argument.startsWith("--")) { "Неизвестный аргумент: $argument" }
                val inline = argument.substringAfter('=', missingDelimiterValue = "")
                val name = argument.substringBefore('=').removePrefix("--")
                val value = if ('=' in argument) inline else args.getOrNull(++index)
                    ?: throw IllegalArgumentException("Для --$name требуется значение")
                require(name in KNOWN_ARGUMENTS) { "Неизвестный аргумент: --$name" }
                require(values.put(name, value) == null) { "Аргумент --$name задан повторно" }
                index++
            }
            val strategy = when (values["strategy"]?.lowercase() ?: "both") {
                "fixed" -> RequestedStrategies.FIXED
                "structured" -> RequestedStrategies.STRUCTURED
                "both" -> RequestedStrategies.BOTH
                else -> throw IllegalArgumentException("--strategy должен быть fixed, structured или both")
            }
            return IndexingCliArguments(
                root = Path.of(values["root"] ?: "."),
                output = Path.of(values["output"] ?: ".llm-document-index"),
                strategies = strategy,
                fixedChunkSize = intValue(values, "fixed-chunk-size", DEFAULT_FIXED_CHUNK_SIZE),
                overlap = intValue(values, "overlap", DEFAULT_FIXED_OVERLAP),
                embeddingModel = values["embedding-model"]?.takeIf(String::isNotBlank) ?: DEFAULT_EMBEDDING_MODEL,
                batchSize = intValue(values, "batch-size", DEFAULT_EMBEDDING_BATCH_SIZE),
            ).also {
                require(it.fixedChunkSize >= 100) { "--fixed-chunk-size должен быть не меньше 100" }
                require(it.overlap in 0 until it.fixedChunkSize) { "--overlap должен быть меньше размера чанка" }
                require(it.batchSize > 0) { "--batch-size должен быть положительным" }
            }
        }

        private val KNOWN_ARGUMENTS = setOf(
            "root", "output", "strategy", "fixed-chunk-size", "overlap", "embedding-model", "batch-size",
        )

        private fun intValue(values: Map<String, String>, name: String, default: Int): Int =
            values[name]?.let { value ->
                value.toIntOrNull() ?: throw IllegalArgumentException("--$name должен быть целым числом")
            } ?: default
    }
}
