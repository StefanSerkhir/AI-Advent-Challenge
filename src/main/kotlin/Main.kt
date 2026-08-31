package org.example

import org.example.cli.parseCliArguments
import org.example.config.loadLlmApiKey
import org.example.llm.LlmApiException
import org.example.llm.createLlmClient
import org.example.network.createHttpClient
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val arguments = try {
        parseCliArguments(args, defaultApiKey = loadLlmApiKey())
    } catch (error: IllegalArgumentException) {
        System.err.println("Ошибка: ${error.message}")
        System.err.println(
            "Использование: --llm_api_key=<ключ> " +
                "[--llm_kind=Deepseek] [текст запроса]. " +
                "Также ключ можно задать в файле .env",
        )
        return@runBlocking
    }

    val httpClient = createHttpClient()
    val llmClient = createLlmClient(arguments.llmKind, arguments.apiKey, httpClient)

    try {
        try {
            println(llmClient.complete(arguments.prompt))
        } catch (error: LlmApiException) {
            System.err.println("Ошибка: ${error.message}")
        }
    } finally {
        httpClient.close()
    }
}
