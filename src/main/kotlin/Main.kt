package org.example

import kotlinx.coroutines.runBlocking
import org.example.app.AppSettings
import org.example.cli.InteractiveCli
import org.example.cli.parseCliArguments
import org.example.config.loadLocalConfig
import org.example.llm.LlmKind
import org.example.llm.createLlmClient
import org.example.network.createHttpClient

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
                "[--llm_kind=Deepseek|OpenAI] [первый запрос]. " +
                "Ключ и тип LLM также можно задать в файле .env",
        )
        return@runBlocking
    }

    val apiKeys = buildMap {
        localConfig.apiKey?.let { put(arguments.llmKind, it) }
        localConfig.deepSeekApiKey?.let { put(LlmKind.DEEPSEEK, it) }
        localConfig.openAiApiKey?.let { put(LlmKind.OPENAI, it) }
        if (arguments.apiKeyProvidedByCli) {
            arguments.apiKey?.let { put(arguments.llmKind, it) }
        }
    }
    val httpClient = createHttpClient()
    val interactiveCli = InteractiveCli(
        settings = AppSettings(llmKind = arguments.llmKind),
        initialApiKeys = apiKeys,
        clientFactory = { kind, apiKey -> createLlmClient(kind, apiKey, httpClient) },
    )

    try {
        interactiveCli.run(arguments.prompt)
    } finally {
        httpClient.close()
    }
}
