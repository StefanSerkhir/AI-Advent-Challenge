package org.example.app

import org.example.config.LocalConfig
import org.example.llm.LlmKind
import org.example.llm.LlmModels

class AppBootstrap private constructor(
    val settings: AppSettings,
    val apiKeys: Map<LlmKind, String>,
    val warning: String?,
) {
    companion object {
        fun from(config: LocalConfig): AppBootstrap {
            val warnings = mutableListOf<String>()
            val llmKind = parseOrDefault("провайдер", LlmKind.DEEPSEEK, warnings) {
                config.llmKind?.let(LlmKind::from) ?: LlmKind.DEEPSEEK
            }
            val responseMode = parseOrDefault("режим", ResponseMode.COMPARE, warnings) {
                config.responseMode?.let(ResponseMode::from)
                    ?: if (config.responseMode == null) ResponseMode.COMPARE else error("unknown mode")
            }

            fun positiveInt(name: String, raw: String?, default: Int): Int {
                if (raw == null) return default
                return raw.toIntOrNull()?.takeIf { it > 0 } ?: run {
                    warnings += "$name в .env имеет неверное значение; используется $default"
                    default
                }
            }

            val historyEnabled = when (config.historyEnabled?.lowercase()) {
                null -> true
                "true", "on", "1" -> true
                "false", "off", "0" -> false
                else -> {
                    warnings += "history_enabled в .env имеет неверное значение; история включена"
                    true
                }
            }
            val settings = AppSettings(
                llmKind = llmKind,
                model = LlmModels.normalize(llmKind, config.model),
                responseMode = responseMode,
                maxTokens = positiveInt("max_tokens", config.maxTokens, 300),
                maxWords = positiveInt("max_words", config.maxWords, 60),
                bulletCount = positiveInt("bullet_count", config.bulletCount, 3),
                stopSequence = when {
                    config.stopSequence == null -> DEFAULT_STOP_SEQUENCE
                    config.stopSequence.equals("off", ignoreCase = true) -> null
                    else -> config.stopSequence
                },
                historyEnabled = historyEnabled,
            )
            val apiKeys = buildMap {
                config.apiKey?.let { put(llmKind, it) }
                config.deepSeekApiKey?.let { put(LlmKind.DEEPSEEK, it) }
                config.openAiApiKey?.let { put(LlmKind.OPENAI, it) }
            }
            return AppBootstrap(settings, apiKeys, warnings.takeIf(List<String>::isNotEmpty)?.joinToString("\n"))
        }

        private inline fun <T> parseOrDefault(
            name: String,
            default: T,
            warnings: MutableList<String>,
            parse: () -> T,
        ): T = try {
            parse()
        } catch (_: Exception) {
            warnings += "$name в .env не распознан; используется значение по умолчанию"
            default
        }
    }
}
