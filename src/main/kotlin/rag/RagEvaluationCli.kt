package org.example.rag

import kotlinx.coroutines.runBlocking
import org.example.app.AppBootstrap
import org.example.app.RagComparisonRunner
import org.example.app.userFacingError
import org.example.config.LocalConfigStore
import org.example.indexing.DEFAULT_OLLAMA_EMBEDDING_MODEL
import org.example.indexing.DocumentRetriever
import org.example.indexing.JsonDocumentIndexStore
import org.example.indexing.createEmbeddingClient
import org.example.llm.LlmKind
import org.example.llm.createLlmClient
import org.example.llm.ollama.DEFAULT_OLLAMA_MODEL
import org.example.network.createHttpClient
import java.nio.file.Path
import kotlin.io.path.absolute

fun main(args: Array<String>) = runBlocking {
    val arguments = RagEvaluationCliArguments.parse(args)
    val root = arguments.root.absolute().normalize()
    val output = if (arguments.output.isAbsolute) arguments.output.normalize() else root.resolve(arguments.output).normalize()
    val bootstrap = AppBootstrap.from(LocalConfigStore(root.resolve(".env")).load())
    val generationKind = LlmKind.OLLAMA
    val generationKey: String? = null
    val model = arguments.model ?: DEFAULT_OLLAMA_MODEL
    val maxTokens = arguments.maxTokens ?: bootstrap.settings.maxTokens

    println("Production RAG evaluation выполнит до 40 локальных generation calls и до 20 локальных embedding queries.")
    println("Enhanced generation пропускается при нуле источников; ошибки также могут уменьшить фактическое число вызовов.")
    println("Cloud comparison выполняется только с --allow-cloud и настроенным openai_api_key; иначе он будет отмечен skipped.")

    val httpClient = createHttpClient(maxRetries = 0)
    try {
        val retriever = DocumentRetriever(
            JsonDocumentIndexStore(root.resolve(".llm-document-index").resolve("structured.json")),
        ) { descriptor ->
            require(descriptor.provider == "ollama") {
                "Local RAG evaluation требует индекс ollama/$DEFAULT_OLLAMA_EMBEDDING_MODEL. Выполните ./gradlew buildLocalDocumentIndex"
            }
            createEmbeddingClient(descriptor.provider, descriptor.model, httpClient)
        }
        val runner = RagComparisonRunner(
            clientProvider = { createLlmClient(generationKind, generationKey, httpClient, model) },
            retrieverProvider = { retriever },
            errorMessage = { userFacingError(it, bootstrap.apiKeys.values) },
        )
        val cloudKey = bootstrap.apiKeys[LlmKind.OPENAI]
        val localTarget = RagGenerationTarget("ollama", model) {
            createLlmClient(LlmKind.OLLAMA, null, httpClient, model)
        }
        val availableTargets = mutableListOf(localTarget)
        val skippedTargets = mutableListOf<Pair<Pair<String, String>, String>>()
        if (arguments.allowCloud && cloudKey != null) {
            availableTargets += RagGenerationTarget("openai", arguments.cloudModel) {
                createLlmClient(LlmKind.OPENAI, cloudKey, httpClient, arguments.cloudModel)
            }
        } else {
            val reason = if (!arguments.allowCloud) {
                "skipped: paid cloud calls were not explicitly allowed with --allow-cloud"
            } else {
                "skipped: openai_api_key is not configured"
            }
            skippedTargets += ("openai" to arguments.cloudModel) to reason
        }
        val providerComparison = RagProviderComparisonRunner(
            retriever = retriever,
            availableTargets = availableTargets,
            skippedTargets = skippedTargets,
            resultLimit = bootstrap.settings.ragResultLimit,
            repeats = arguments.repetitions,
            timeoutMillis = arguments.timeoutSeconds * 1_000L,
            errorMessage = { userFacingError(it, bootstrap.apiKeys.values) },
        ).run(
            cases = listOf(RAG_EVALUATION_CASES[0], RAG_EVALUATION_CASES[3], RAG_EVALUATION_CASES[8]),
            maxTokens = maxTokens,
        )
        val report = RagEvaluationRunner(
            runner,
            ragCandidateLimit = bootstrap.settings.ragCandidateLimit,
            ragResultLimit = bootstrap.settings.ragResultLimit,
            ragMinSimilarity = bootstrap.settings.ragMinSimilarity,
        ).run(model, maxTokens, provider = "ollama", providerComparison = providerComparison)
        val jsonFile = output.resolve("comparison.json")
        val markdownFile = output.resolve("comparison.md")
        RagEvaluationReportStore(jsonFile, markdownFile).save(report)
        println("RAG evaluation сохранён: $jsonFile")
        println("Markdown-отчёт: $markdownFile")
        println("Автоматически рассчитаны source/quote/citation/exact-substring/usage metrics; semantic support 0–2 оставлен для ручной оценки.")
    } finally {
        httpClient.close()
    }
}

data class RagEvaluationCliArguments(
    val root: Path = Path.of("."),
    val output: Path = Path.of(".llm-rag-evaluation"),
    val model: String? = null,
    val maxTokens: Int? = null,
    val allowCloud: Boolean = false,
    val cloudModel: String = "gpt-4.1-mini",
    val repetitions: Int = DEFAULT_RAG_STABILITY_REPEATS,
    val timeoutSeconds: Long = DEFAULT_RAG_PROVIDER_TIMEOUT_MILLIS / 1_000L,
) {
    companion object {
        fun parse(args: Array<String>): RagEvaluationCliArguments {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val raw = args[index]
                require(raw.startsWith("--")) { "Неизвестный аргумент: $raw" }
                val name = raw.substringBefore('=').removePrefix("--")
                require(name in setOf("root", "output", "model", "max-tokens", "allow-cloud", "cloud-model", "repetitions", "timeout-seconds")) {
                    "Неизвестный аргумент: --$name"
                }
                val value = if (name == "allow-cloud" && '=' !in raw) {
                    "true"
                } else if ('=' in raw) {
                    raw.substringAfter('=')
                } else {
                    args.getOrNull(++index) ?: error("Для --$name требуется значение")
                }
                require(values.put(name, value) == null) { "Аргумент --$name задан повторно" }
                index++
            }
            val maxTokens = values["max-tokens"]?.toIntOrNull()
            require(maxTokens == null || maxTokens > 0) { "--max-tokens должен быть положительным целым числом" }
            val repetitions = values["repetitions"]?.toIntOrNull() ?: DEFAULT_RAG_STABILITY_REPEATS
            require(repetitions >= 3) { "--repetitions должен быть не меньше 3" }
            val timeoutSeconds = values["timeout-seconds"]?.toLongOrNull() ?: DEFAULT_RAG_PROVIDER_TIMEOUT_MILLIS / 1_000L
            require(timeoutSeconds > 0) { "--timeout-seconds должен быть положительным целым числом" }
            val allowCloud = values["allow-cloud"]?.toBooleanStrictOrNull() ?: false
            return RagEvaluationCliArguments(
                root = Path.of(values["root"] ?: "."),
                output = Path.of(values["output"] ?: ".llm-rag-evaluation"),
                model = values["model"]?.takeIf(String::isNotBlank),
                maxTokens = maxTokens,
                allowCloud = allowCloud,
                cloudModel = values["cloud-model"]?.takeIf(String::isNotBlank) ?: "gpt-4.1-mini",
                repetitions = repetitions,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }
}
