package org.example.config

import java.nio.file.Files
import java.nio.file.Path

private const val API_KEY_NAME = "llm_api_key"

fun loadLlmApiKey(envFile: Path = Path.of(".env")): String? {
    val environmentValue = System.getenv(API_KEY_NAME).normalizedValue()
    if (environmentValue != null) return environmentValue

    if (!Files.exists(envFile)) return null

    return Files.readAllLines(envFile)
        .firstOrNull { it.substringBefore("=").trim() == API_KEY_NAME }
        ?.substringAfter("=")
        .normalizedValue()
}

private fun String?.normalizedValue(): String? = this
    ?.trim()
    ?.removeSurrounding("\"")
    ?.takeIf { it.isNotBlank() }
