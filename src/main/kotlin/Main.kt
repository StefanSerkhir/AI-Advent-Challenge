package org.example

import org.example.cli.parseCliArguments
import org.example.config.loadLocalConfig
import org.example.llm.CompletionOptions
import org.example.llm.LlmApiException
import org.example.llm.createLlmClient
import org.example.network.createHttpClient
import kotlinx.coroutines.runBlocking

private const val STOP_SEQUENCE = "<END_OF_RESPONSE>"
private const val CONTROLLED_MAX_TOKENS = 300

internal fun withResponseConstraints(prompt: String): String = """
    $prompt

    Требования к ответу:
    - Формат: ровно 3 пункта маркированного списка; каждый пункт начинай с «- ».
    - Длина: не более 60 слов во всём ответе.
    - Не добавляй заголовок, вступление или заключение.
    - После третьего пункта напиши $STOP_SEQUENCE и сразу заверши ответ.
""".trimIndent()

fun main(args: Array<String>) = runBlocking {
    val localConfig = loadLocalConfig()
    val arguments = try {
        parseCliArguments(
            args = args,
            defaultApiKey = localConfig.apiKey,
            defaultLlmKind = localConfig.llmKind,
        )
    } catch (error: IllegalArgumentException) {
        System.err.println("Ошибка: ${error.message}")
        System.err.println(
            "Использование: --llm_api_key=<ключ> " +
                "[--llm_kind=Deepseek|OpenAI] [текст запроса]. " +
                "Ключ и тип LLM также можно задать в файле .env",
        )
        return@runBlocking
    }

    val httpClient = createHttpClient()
    val llmClient = createLlmClient(arguments.llmKind, arguments.apiKey, httpClient)

    try {
        try {
            val unrestrictedResponse = llmClient.complete(arguments.prompt)
            val controlledResponse = llmClient.complete(
                prompt = withResponseConstraints(arguments.prompt),
                options = CompletionOptions(
                    maxTokens = CONTROLLED_MAX_TOKENS,
                    stopSequences = listOf(STOP_SEQUENCE),
                ),
            )

            println("=== БЕЗ ОГРАНИЧЕНИЙ ===")
            println(unrestrictedResponse)
            println()
            println("=== С ОГРАНИЧЕНИЯМИ ===")
            println(controlledResponse)
        } catch (error: LlmApiException) {
            System.err.println("Ошибка: ${error.message}")
        }
    } finally {
        httpClient.close()
    }
}
