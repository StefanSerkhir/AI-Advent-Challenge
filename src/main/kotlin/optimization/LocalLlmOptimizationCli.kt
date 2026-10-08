package org.example.optimization

import kotlinx.coroutines.runBlocking
import org.example.indexing.DEFAULT_OLLAMA_EMBEDDING_MODEL
import org.example.indexing.DocumentRetriever
import org.example.indexing.JsonDocumentIndexStore
import org.example.indexing.createEmbeddingClient
import org.example.llm.LlmKind
import org.example.llm.createLlmClient
import org.example.network.createHttpClient
import java.nio.file.Path
import kotlin.io.path.absolute

fun main(args: Array<String>) = runBlocking {
    val arguments = OptimizationCliArguments.parse(args)
    val root = arguments.root.absolute().normalize()
    val output = if (arguments.output.isAbsolute) arguments.output.normalize() else root.resolve(arguments.output).normalize()
    val installedModels = runCommand(listOf("ollama", "list")).orEmpty().lineSequence().drop(1)
        .map { it.trim().substringBefore(' ') }.toSet()
    require(BASE_MODEL in installedModels) { "Модель $BASE_MODEL не установлена. Выполните: ollama pull $BASE_MODEL" }
    require(installedModels.any { it == OPTIMIZED_MODEL_ALIAS || it.substringBefore(':') == OPTIMIZED_MODEL_ALIAS.substringBefore(':') }) {
        "Context alias $OPTIMIZED_MODEL_ALIAS не создан. Выполните: ollama create ${OPTIMIZED_MODEL_ALIAS.substringBefore(':')} -f ollama/Modelfile.qwen3-14b-rag-optimized"
    }
    println("Real-local optimization: 10 canonical RAG cases + 1 abstention case; cloud/paid APIs are disabled.")
    println("Staged search: temperature 0.0/0.2/0.6, max tokens 256/384/600, actual allocation context 4096/8192, prompt baseline-v1/repo-tech-v2.")
    println("Final A/B: one excluded warm-up and ${arguments.repetitions} measured repeats for every case/configuration.")

    val httpClient = createHttpClient(maxRetries = 0)
    try {
        val retriever = DocumentRetriever(
            JsonDocumentIndexStore(root.resolve(".llm-document-index/structured.json")),
        ) { descriptor ->
            require(descriptor.provider == "ollama" && descriptor.model == DEFAULT_OLLAMA_EMBEDDING_MODEL) {
                "Optimization requires ollama/$DEFAULT_OLLAMA_EMBEDDING_MODEL index. Run ./gradlew buildLocalDocumentIndex"
            }
            createEmbeddingClient(descriptor.provider, descriptor.model, httpClient)
        }
        val report = LocalLlmOptimizationRunner(
            retriever = retriever,
            clientProvider = { model -> createLlmClient(LlmKind.OLLAMA, null, httpClient, model) },
            timeoutMillis = arguments.timeoutSeconds * 1_000L,
            repetitions = arguments.repetitions,
            store = OptimizationReportStore(output.resolve("optimization.json"), output.resolve("optimization.md")),
            environment = captureHostEnvironment(),
        ).run()
        println("Optimization complete: ${report.decision?.winnerConfigurationId}")
        println("JSON: ${output.resolve("optimization.json")}")
        println("Markdown: ${output.resolve("optimization.md")}")
    } finally {
        httpClient.close()
    }
}

data class OptimizationCliArguments(
    val root: Path = Path.of("."),
    val output: Path = Path.of(".llm-local-optimization"),
    val repetitions: Int = 3,
    val timeoutSeconds: Long = 180,
) {
    companion object {
        fun parse(args: Array<String>): OptimizationCliArguments {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val raw = args[index]
                require(raw.startsWith("--")) { "Неизвестный аргумент: $raw" }
                val name = raw.substringBefore('=').removePrefix("--")
                require(name in setOf("root", "output", "repetitions", "timeout-seconds")) { "Неизвестный аргумент: --$name" }
                val value = if ('=' in raw) raw.substringAfter('=') else args.getOrNull(++index)
                    ?: error("Для --$name требуется значение")
                require(values.put(name, value) == null) { "Аргумент --$name задан повторно" }
                index++
            }
            val repetitions = values["repetitions"]?.toIntOrNull() ?: 3
            require(repetitions >= 3) { "--repetitions должен быть не меньше 3" }
            val timeout = values["timeout-seconds"]?.toLongOrNull() ?: 180
            require(timeout > 0) { "--timeout-seconds должен быть положительным" }
            return OptimizationCliArguments(
                root = Path.of(values["root"] ?: "."),
                output = Path.of(values["output"] ?: ".llm-local-optimization"),
                repetitions = repetitions,
                timeoutSeconds = timeout,
            )
        }
    }
}
