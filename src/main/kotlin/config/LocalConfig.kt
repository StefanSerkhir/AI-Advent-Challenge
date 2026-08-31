package org.example.config

import java.nio.file.Files
import java.nio.file.Path

private const val API_KEY_NAME = "llm_api_key"
private const val LLM_KIND_NAME = "llm_kind"

data class LocalConfig(
    val apiKey: String?,
    val llmKind: String?,
)

fun loadLocalConfig(envFile: Path = Path.of(".env")): LocalConfig {
    val fileValues = if (Files.exists(envFile)) {
        Files.readAllLines(envFile).mapNotNull { line ->
            val name = line.substringBefore("=").trim()
            val value = line.substringAfter("=", missingDelimiterValue = "").normalizedValue()
            value?.let { name to it }
        }.toMap()
    } else {
        emptyMap()
    }

    return LocalConfig(
        apiKey = loadValue(API_KEY_NAME, fileValues),
        llmKind = loadValue(LLM_KIND_NAME, fileValues),
    )
}

private fun loadValue(name: String, fileValues: Map<String, String>): String? =
    System.getenv(name).normalizedValue() ?: fileValues[name]

private fun String?.normalizedValue(): String? = this
    ?.trim()
    ?.removeSurrounding("\"")
    ?.takeIf { it.isNotBlank() }
