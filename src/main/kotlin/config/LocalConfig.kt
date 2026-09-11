package org.example.config

import org.example.app.AppSettings
import org.example.llm.LlmKind
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val API_KEY_NAME = "llm_api_key"
private const val LLM_KIND_NAME = "llm_kind"
private const val LLM_MODEL_NAME = "llm_model"
private const val DEEPSEEK_API_KEY_NAME = "deepseek_api_key"
private const val OPENAI_API_KEY_NAME = "openai_api_key"
private const val RESPONSE_MODE_NAME = "response_mode"
private const val MAX_TOKENS_NAME = "max_tokens"
private const val MAX_WORDS_NAME = "max_words"
private const val BULLET_COUNT_NAME = "bullet_count"
private const val STOP_SEQUENCE_NAME = "stop_sequence"
private const val HISTORY_ENABLED_NAME = "history_enabled"
private const val CONTEXT_OVERFLOW_POLICY_NAME = "context_overflow_policy"
private const val CONTEXT_STRATEGY_NAME = "context_strategy"
private const val RECENT_MESSAGES_LIMIT_NAME = "recent_messages_limit"

data class LocalConfig(
    val apiKey: String? = null,
    val llmKind: String? = null,
    val model: String? = null,
    val deepSeekApiKey: String? = null,
    val openAiApiKey: String? = null,
    val responseMode: String? = null,
    val maxTokens: String? = null,
    val maxWords: String? = null,
    val bulletCount: String? = null,
    val stopSequence: String? = null,
    val historyEnabled: String? = null,
    val contextOverflowPolicy: String? = null,
    val contextStrategy: String? = null,
    val recentMessagesLimit: String? = null,
) {
    // Prevent accidental disclosure if the object reaches a logger or assertion message.
    override fun toString(): String = "LocalConfig(" +
        "apiKey=${apiKey.redacted()}, llmKind=$llmKind, model=$model, " +
        "deepSeekApiKey=${deepSeekApiKey.redacted()}, openAiApiKey=${openAiApiKey.redacted()}, " +
        "responseMode=$responseMode, maxTokens=$maxTokens, maxWords=$maxWords, " +
        "bulletCount=$bulletCount, stopSequence=$stopSequence, historyEnabled=$historyEnabled, contextOverflowPolicy=$contextOverflowPolicy, " +
        "contextStrategy=$contextStrategy, recentMessagesLimit=$recentMessagesLimit)"
}

class LocalConfigStore(
    private val envFile: Path = Path.of(".env"),
    private val environment: Map<String, String> = System.getenv(),
) {
    @Synchronized
    fun load(): LocalConfig {
        val fileValues = readFileValues()
        return LocalConfig(
            apiKey = loadValue(API_KEY_NAME, fileValues),
            llmKind = loadValue(LLM_KIND_NAME, fileValues),
            model = loadValue(LLM_MODEL_NAME, fileValues),
            deepSeekApiKey = loadValue(DEEPSEEK_API_KEY_NAME, fileValues),
            openAiApiKey = loadValue(OPENAI_API_KEY_NAME, fileValues),
            responseMode = loadValue(RESPONSE_MODE_NAME, fileValues),
            maxTokens = loadValue(MAX_TOKENS_NAME, fileValues),
            maxWords = loadValue(MAX_WORDS_NAME, fileValues),
            bulletCount = loadValue(BULLET_COUNT_NAME, fileValues),
            stopSequence = loadValue(STOP_SEQUENCE_NAME, fileValues),
            historyEnabled = loadValue(HISTORY_ENABLED_NAME, fileValues),
            contextOverflowPolicy = loadValue(CONTEXT_OVERFLOW_POLICY_NAME, fileValues),
            contextStrategy = loadValue(CONTEXT_STRATEGY_NAME, fileValues),
            recentMessagesLimit = loadValue(RECENT_MESSAGES_LIMIT_NAME, fileValues),
        )
    }

    @Synchronized
    fun save(settings: AppSettings, apiKeys: Map<LlmKind, String>) {
        val updates = linkedMapOf(
            LLM_KIND_NAME to settings.llmKind.configValue(),
            LLM_MODEL_NAME to settings.model,
            RESPONSE_MODE_NAME to settings.responseMode.cliValue,
            MAX_TOKENS_NAME to settings.maxTokens.toString(),
            MAX_WORDS_NAME to settings.maxWords.toString(),
            BULLET_COUNT_NAME to settings.bulletCount.toString(),
            STOP_SEQUENCE_NAME to (settings.stopSequence ?: "off"),
            HISTORY_ENABLED_NAME to settings.historyEnabled.toString(),
            CONTEXT_OVERFLOW_POLICY_NAME to settings.contextOverflowPolicy.name,
            CONTEXT_STRATEGY_NAME to settings.contextStrategy.name,
            RECENT_MESSAGES_LIMIT_NAME to settings.recentMessagesLimit.toString(),
        )
        apiKeys[LlmKind.DEEPSEEK]?.let { updates[DEEPSEEK_API_KEY_NAME] = it }
        apiKeys[LlmKind.OPENAI]?.let { updates[OPENAI_API_KEY_NAME] = it }
        require(updates.values.none { '\n' in it || '\r' in it }) {
            "Значения конфигурации не могут содержать перевод строки"
        }

        val absoluteFile = envFile.toAbsolutePath()
        val parent = absoluteFile.parent
        Files.createDirectories(parent)
        val originalLines = if (Files.exists(absoluteFile)) {
            Files.readAllLines(absoluteFile, StandardCharsets.UTF_8)
        } else {
            emptyList()
        }
        val remaining = updates.toMutableMap()
        val outputLines = originalLines.mapNotNull { line ->
            val name = assignmentName(line)
            val replacement = name?.let(remaining::remove)
            when {
                name == API_KEY_NAME -> null // Migrate the ambiguous legacy key to provider-specific entries.
                name in setOf("context_management_enabled", "summarization_batch_size") -> null
                replacement != null -> "$name=${encodeValue(replacement)}"
                name != null && name in updates -> null // Drop duplicate assignments.
                else -> line
            }
        }.toMutableList()

        if (outputLines.isNotEmpty() && outputLines.last().isNotBlank() && remaining.isNotEmpty()) {
            outputLines += ""
        }
        remaining.forEach { (name, value) -> outputLines += "$name=${encodeValue(value)}" }

        val temporaryFile = Files.createTempFile(parent, ".env-", ".tmp")
        try {
            Files.write(temporaryFile, outputLines, StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporaryFile,
                    absoluteFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, absoluteFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun readFileValues(): Map<String, String> = if (Files.exists(envFile)) {
        Files.readAllLines(envFile, StandardCharsets.UTF_8).mapNotNull { line ->
            val name = assignmentName(line) ?: return@mapNotNull null
            decodeValue(line.substringAfter("="))?.let { name to it }
        }.toMap()
    } else {
        emptyMap()
    }

    private fun loadValue(name: String, fileValues: Map<String, String>): String? =
        environment[name].normalizedValue() ?: fileValues[name]
}

fun loadLocalConfig(envFile: Path = Path.of(".env")): LocalConfig = LocalConfigStore(envFile).load()

private fun assignmentName(line: String): String? {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("#") || trimmed.startsWith("//")) return null
    val name = line.substringBefore("=", missingDelimiterValue = "").trim()
    return name.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
}

private fun decodeValue(rawValue: String): String? {
    val value = rawValue.trim()
    if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
        return value.substring(1, value.lastIndex)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .takeIf(String::isNotBlank)
    }
    return value.takeIf(String::isNotBlank)
}

private fun encodeValue(value: String): String = if (
    value.any { it.isWhitespace() || it == '#' || it == '"' || it == '\\' }
) {
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
} else {
    value
}

private fun String?.normalizedValue(): String? = this
    ?.trim()
    ?.removeSurrounding("\"")
    ?.takeIf(String::isNotBlank)

private fun LlmKind.configValue(): String = when (this) {
    LlmKind.DEEPSEEK -> "Deepseek"
    LlmKind.OPENAI -> "OpenAI"
}

private fun String?.redacted(): String = if (this == null) "null" else "<redacted>"
