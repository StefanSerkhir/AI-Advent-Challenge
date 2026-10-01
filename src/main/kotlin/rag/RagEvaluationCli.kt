package org.example.rag

import kotlinx.coroutines.runBlocking
import org.example.app.AppBootstrap
import org.example.app.RagComparisonRunner
import org.example.app.userFacingError
import org.example.config.LocalConfigStore
import org.example.indexing.DocumentRetriever
import org.example.indexing.JsonDocumentIndexStore
import org.example.indexing.OpenAiEmbeddingClient
import org.example.llm.LlmKind
import org.example.llm.createLlmClient
import org.example.network.createHttpClient
import java.nio.file.Path
import kotlin.io.path.absolute

fun main(args: Array<String>) = runBlocking {
    val arguments = RagEvaluationCliArguments.parse(args)
    val root = arguments.root.absolute().normalize()
    val output = if (arguments.output.isAbsolute) arguments.output.normalize() else root.resolve(arguments.output).normalize()
    val bootstrap = AppBootstrap.from(LocalConfigStore(root.resolve(".env")).load())
    val generationKind = bootstrap.settings.llmKind
    val generationKey = bootstrap.apiKeys[generationKind]
        ?: error("Для выбранного generation provider ${generationKind.name} не настроен API-ключ.")
    val embeddingKey = bootstrap.apiKeys[LlmKind.OPENAI]
        ?: error("Для query embeddings не настроен openai_api_key.")
    val model = arguments.model ?: bootstrap.settings.model
    val maxTokens = arguments.maxTokens ?: bootstrap.settings.maxTokens

    println("ВНИМАНИЕ: production RAG evaluation выполнит до 40 generation calls и до 20 embedding queries и может быть платной.")
    println("Enhanced generation пропускается при нуле источников; ошибки также могут уменьшить фактическое число вызовов.")
    println("Запуск был инициирован явно; автоматические тесты эту команду не вызывают.")

    val httpClient = createHttpClient(maxRetries = 0)
    try {
        val retriever = DocumentRetriever(
            JsonDocumentIndexStore(root.resolve(".llm-document-index").resolve("structured.json")),
        ) { descriptor ->
            require(descriptor.provider == "openai") {
                "Evaluation CLI поддерживает query embeddings только для OpenAI index descriptor"
            }
            OpenAiEmbeddingClient(embeddingKey, httpClient, descriptor.model)
        }
        val runner = RagComparisonRunner(
            clientProvider = { createLlmClient(generationKind, generationKey, httpClient, model) },
            retrieverProvider = { retriever },
            errorMessage = { userFacingError(it, bootstrap.apiKeys.values) },
        )
        val report = RagEvaluationRunner(
            runner,
            ragCandidateLimit = bootstrap.settings.ragCandidateLimit,
            ragResultLimit = bootstrap.settings.ragResultLimit,
            ragMinSimilarity = bootstrap.settings.ragMinSimilarity,
        ).run(model, maxTokens)
        val jsonFile = output.resolve("comparison.json")
        val markdownFile = output.resolve("comparison.md")
        RagEvaluationReportStore(jsonFile, markdownFile).save(report)
        println("RAG evaluation сохранён: $jsonFile")
        println("Markdown-отчёт: $markdownFile")
        println("Автоматически рассчитаны retrieval/source/citation/usage metrics; оценки 0–2 оставлены для ручного заполнения.")
    } finally {
        httpClient.close()
    }
}

data class RagEvaluationCliArguments(
    val root: Path = Path.of("."),
    val output: Path = Path.of(".llm-rag-evaluation"),
    val model: String? = null,
    val maxTokens: Int? = null,
) {
    companion object {
        fun parse(args: Array<String>): RagEvaluationCliArguments {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val raw = args[index]
                require(raw.startsWith("--")) { "Неизвестный аргумент: $raw" }
                val name = raw.substringBefore('=').removePrefix("--")
                require(name in setOf("root", "output", "model", "max-tokens")) { "Неизвестный аргумент: --$name" }
                val value = if ('=' in raw) raw.substringAfter('=') else args.getOrNull(++index)
                    ?: error("Для --$name требуется значение")
                require(values.put(name, value) == null) { "Аргумент --$name задан повторно" }
                index++
            }
            val maxTokens = values["max-tokens"]?.toIntOrNull()
            require(maxTokens == null || maxTokens > 0) { "--max-tokens должен быть положительным целым числом" }
            return RagEvaluationCliArguments(
                root = Path.of(values["root"] ?: "."),
                output = Path.of(values["output"] ?: ".llm-rag-evaluation"),
                model = values["model"]?.takeIf(String::isNotBlank),
                maxTokens = maxTokens,
            )
        }
    }
}
