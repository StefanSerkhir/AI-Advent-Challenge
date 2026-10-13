package org.example.review

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.example.indexing.*
import org.example.llm.openai.OpenAiLlmClient
import org.example.network.createHttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute

fun main(args: Array<String>) = runBlocking {
    val arguments = AiCodeReviewCliArguments.parse(args)
    if (arguments.fixture) {
        error("Production CLI does not package fakes. Use ./gradlew runAiCodeReviewFixture for the offline fixture.")
    }
    val root = arguments.root.absolute().normalize()
    val output = if (arguments.output.isAbsolute) arguments.output.normalize() else root.resolve(arguments.output).normalize()
    val eventMetadata = arguments.eventFile?.let(GitHubPullRequestEventLoader()::load)
    val metadata = arguments.toMetadata(eventMetadata)
    val openAiKey = System.getenv("OPENAI_API_KEY").orEmpty()
    val githubToken = System.getenv("GITHUB_TOKEN").orEmpty()
    when (reviewRunEligibility(metadata, openAiKey.isNotBlank(), githubToken.isNotBlank(), !arguments.dryRun)) {
        ReviewRunEligibility.DRAFT -> {
            println("AI REVIEW SKIPPED: draft pull request; paid calls: 0")
            return@runBlocking
        }
        ReviewRunEligibility.FORK -> {
            println("AI REVIEW SKIPPED: fork pull request has no trusted secrets/write token; paid calls: 0")
            return@runBlocking
        }
        ReviewRunEligibility.MISSING_OPENAI_KEY -> error("OPENAI_API_KEY is not available; review was not started")
        ReviewRunEligibility.MISSING_GITHUB_TOKEN -> error("GITHUB_TOKEN is not available for publication; use --dry-run for local output only")
        ReviewRunEligibility.ELIGIBLE -> Unit
    }

    // Verify the exact clean base checkout and collect head data before any indexing or paid network call.
    val collectedDiff = GitPullRequestDiffCollector().collect(root, metadata.baseSha, metadata.headSha)
    Files.createDirectories(output)
    val indexDirectory = output.resolve("index")
    val httpClient = createHttpClient()
    try {
        val indexing = DocumentIndexPipeline(
            embeddingClient = OpenAiEmbeddingClient(openAiKey, httpClient),
            knownSecrets = listOf(openAiKey, githubToken),
        ).run(
            IndexingOptions(
                corpusRoot = root,
                outputDirectory = indexDirectory,
                strategies = RequestedStrategies.STRUCTURED,
                productionEmbeddings = true,
            ),
        )
        check(indexing.indexes.size == 1) { "Review index must contain exactly the structured strategy" }
        val indexFile = indexDirectory.resolve("structured.json")
        val retriever = DocumentRetriever(JsonDocumentIndexStore(indexFile)) { descriptor ->
            require(descriptor.provider == EmbeddingProvider.OPENAI.wireName) {
                "CI review index must use OpenAI embeddings"
            }
            OpenAiEmbeddingClient(openAiKey, httpClient, descriptor.model)
        }
        val redactor = SecretRedactor(listOf(openAiKey, githubToken))
        val publisher = if (arguments.dryRun) null else GitHubReviewPublisher(
            httpClient = httpClient,
            token = githubToken,
            repository = metadata.repository,
            pullRequestNumber = metadata.number,
        )
        val artifact = AiCodeReviewPipeline(
            diffSource = PullRequestDiffSource { collectedDiff },
            ragProvider = RepositoryReviewRagProvider(retriever),
            llmClient = OpenAiLlmClient(openAiKey, httpClient, arguments.model),
            model = arguments.model,
            outputDirectory = output,
            publisher = publisher,
            diffLimiter = ReviewDiffLimiter(arguments.diffLimit),
            promptBuilder = ReviewPromptBuilder(redactor = redactor),
            validator = ReviewOutputValidator(redactor = redactor),
            renderer = ReviewMarkdownRenderer(redactor),
            maxCompletionTokens = arguments.maxTokens,
        ).run(metadata)
        println(
            "AI REVIEW COMPLETED: verdict=${artifact.result.verdict}, findings=${artifact.result.findings.size}, " +
                "files=${artifact.diagnostics.changedFileCount}, coverage=${if (artifact.result.partial) "partial" else "full"}, " +
                "published=${publisher != null}",
        )
        println("Artifacts: ${output.resolve("review.md").fileName}, ${output.resolve("review.json").fileName}")
    } finally {
        httpClient.close()
    }
}

data class AiCodeReviewCliArguments(
    val root: Path = Path.of("."),
    val baseSha: String,
    val headSha: String,
    val pullRequestNumber: Int,
    val output: Path = Path.of("build/ai-code-review"),
    val model: String = DEFAULT_AI_REVIEW_MODEL,
    val dryRun: Boolean = false,
    val fixture: Boolean = false,
    val eventFile: Path? = null,
    val repository: String? = null,
    val title: String? = null,
    val body: String? = null,
    val diffLimit: Int = DEFAULT_REVIEW_DIFF_LIMIT,
    val maxTokens: Int = DEFAULT_REVIEW_MAX_TOKENS,
) {
    companion object {
        fun parse(args: Array<String>): AiCodeReviewCliArguments {
            val values = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var index = 0
            while (index < args.size) {
                val argument = args[index]
                require(argument.startsWith("--")) { "Неизвестный аргумент: $argument" }
                val name = argument.substringBefore('=').removePrefix("--")
                require(name in VALUE_ARGUMENTS || name in FLAG_ARGUMENTS) { "Неизвестный аргумент: --$name" }
                if (name in FLAG_ARGUMENTS) {
                    require('=' !in argument) { "Флаг --$name не принимает значение" }
                    require(flags.add(name)) { "Флаг --$name задан повторно" }
                } else {
                    val value = if ('=' in argument) argument.substringAfter('=') else args.getOrNull(++index)
                        ?: throw IllegalArgumentException("Для --$name требуется значение")
                    require(values.put(name, value) == null) { "Аргумент --$name задан повторно" }
                }
                index++
            }
            val base = validateCommitSha(values["base-sha"] ?: error("Требуется --base-sha"), "base SHA")
            val head = validateCommitSha(values["head-sha"] ?: error("Требуется --head-sha"), "head SHA")
            val pr = positiveInt(values["pr-number"], "pr-number")
            val diffLimit = values["diff-limit"]?.let { positiveInt(it, "diff-limit") } ?: DEFAULT_REVIEW_DIFF_LIMIT
            val maxTokens = values["max-tokens"]?.let { positiveInt(it, "max-tokens") } ?: DEFAULT_REVIEW_MAX_TOKENS
            return AiCodeReviewCliArguments(
                root = Path.of(values["root"] ?: "."),
                baseSha = base,
                headSha = head,
                pullRequestNumber = pr,
                output = Path.of(values["output"] ?: "build/ai-code-review"),
                model = values["model"] ?: DEFAULT_AI_REVIEW_MODEL,
                dryRun = "dry-run" in flags,
                fixture = "fixture" in flags,
                eventFile = values["event-file"]?.let(Path::of),
                repository = values["repository"],
                title = values["title"],
                body = values["body"],
                diffLimit = diffLimit,
                maxTokens = maxTokens,
            ).also {
                require(it.model.matches(Regex("gpt-5\\.6-(?:luna|terra|sol)"))) { "--model должен быть gpt-5.6-luna, gpt-5.6-terra или gpt-5.6-sol" }
            }
        }

        private fun positiveInt(value: String?, name: String): Int = value?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("--$name должен быть положительным целым числом")

        private val FLAG_ARGUMENTS = setOf("dry-run", "fixture")
        private val VALUE_ARGUMENTS = setOf(
            "root", "base-sha", "head-sha", "pr-number", "output", "model", "event-file", "repository", "title", "body",
            "diff-limit", "max-tokens",
        )
    }

    fun toMetadata(event: PullRequestMetadata?): PullRequestMetadata {
        if (event != null) {
            require(event.baseSha == baseSha) { "--base-sha не совпадает с GitHub event" }
            require(event.headSha == headSha) { "--head-sha не совпадает с GitHub event" }
            require(event.number == pullRequestNumber) { "--pr-number не совпадает с GitHub event" }
            return event
        }
        val repo = repository ?: error("Без --event-file требуется --repository")
        return PullRequestMetadata(
            repository = repo,
            number = pullRequestNumber,
            title = title.orEmpty(),
            body = body.orEmpty(),
            baseSha = baseSha,
            headSha = headSha,
        )
    }
}

class GitHubPullRequestEventLoader(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun load(path: Path): PullRequestMetadata {
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        val pullRequest = root["pull_request"]?.jsonObject ?: error("GitHub event does not contain pull_request")
        val base = pullRequest.getValue("base").jsonObject
        val head = pullRequest.getValue("head").jsonObject
        val repository = root.getValue("repository").jsonObject.getValue("full_name").jsonPrimitive.content
        return PullRequestMetadata(
            repository = repository,
            number = root.getValue("number").jsonPrimitive.int,
            title = pullRequest.getValue("title").jsonPrimitive.content,
            body = pullRequest["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            baseSha = validateCommitSha(base.getValue("sha").jsonPrimitive.content, "event base SHA"),
            headSha = validateCommitSha(head.getValue("sha").jsonPrimitive.content, "event head SHA"),
            baseRepository = base.getValue("repo").jsonObject.getValue("full_name").jsonPrimitive.content,
            headRepository = head.getValue("repo").jsonObject.getValue("full_name").jsonPrimitive.content,
            draft = pullRequest["draft"]?.jsonPrimitive?.boolean ?: false,
        )
    }
}
