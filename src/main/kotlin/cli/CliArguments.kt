package org.example.cli

import org.example.llm.LlmKind

data class CliArguments(
    val apiKey: String,
    val llmKind: LlmKind,
    val prompt: String,
)

fun parseCliArguments(
    args: Array<String>,
    defaultApiKey: String? = null,
    defaultLlmKind: String? = null,
): CliArguments {
    var apiKey = defaultApiKey
    var llmKind = defaultLlmKind?.let(LlmKind::from) ?: LlmKind.DEEPSEEK
    val promptParts = mutableListOf<String>()

    var index = 0
    while (index < args.size) {
        val argument = args[index]
        when {
            argument == "--llm_api_key" -> {
                apiKey = args.getOrNull(++index)
                    ?.takeUnless { it.startsWith("--") }
                    ?: throw IllegalArgumentException("не задано значение --llm_api_key")
            }

            argument.startsWith("--llm_api_key=") -> {
                apiKey = argument.substringAfter("=").ifBlank {
                    throw IllegalArgumentException("не задано значение --llm_api_key")
                }
            }

            argument == "--llm_kind" -> {
                val value = args.getOrNull(++index)
                    ?.takeUnless { it.startsWith("--") }
                    ?: throw IllegalArgumentException("не задано значение --llm_kind")
                llmKind = LlmKind.from(value)
            }

            argument.startsWith("--llm_kind=") -> {
                llmKind = LlmKind.from(argument.substringAfter("="))
            }

            argument.startsWith("--") -> {
                throw IllegalArgumentException("неизвестный параметр $argument")
            }

            else -> promptParts += argument
        }
        index++
    }

    return CliArguments(
        apiKey = apiKey ?: throw IllegalArgumentException(
            "ключ LLM не найден: передайте --llm_api_key " +
                "или заполните llm_api_key в .env",
        ),
        llmKind = llmKind,
        prompt = promptParts.joinToString(" ")
            .ifBlank { "Привет! Расскажи короткую шутку." },
    )
}
