package org.example.cli

import kotlinx.coroutines.CancellationException
import org.example.app.AppSettings
import org.example.app.PromptRunner
import org.example.app.ResponseMode
import org.example.app.ResponseVariant
import org.example.app.renderComparisonTable
import org.example.llm.LlmApiException
import org.example.llm.LlmClient
import org.example.llm.LlmKind

interface Terminal {
    fun readLine(): String?
    fun print(text: String)
    fun println(text: String = "")
    fun setTitle(title: String)
}

object SystemTerminal : Terminal {
    private val supportsTitle = (
        System.getenv("TERM")
            ?.takeUnless { it.equals("dumb", ignoreCase = true) }
        ) != null

    override fun readLine(): String? = readlnOrNull()

    override fun print(text: String) {
        kotlin.io.print(text)
        System.out.flush()
    }

    override fun println(text: String) = kotlin.io.println(text)

    override fun setTitle(title: String) {
        if (supportsTitle) {
            kotlin.io.print("\u001B]0;$title\u0007")
            System.out.flush()
        }
    }
}

class InteractiveCli(
    val settings: AppSettings,
    initialApiKeys: Map<LlmKind, String>,
    clientFactory: (LlmKind, String) -> LlmClient,
    private val terminal: Terminal = SystemTerminal,
) {
    private val apiKeys = initialApiKeys.toMutableMap()
    private val promptRunner = PromptRunner {
        val apiKey = apiKeys[settings.llmKind] ?: throw MissingApiKeyException(
            "API-ключ для ${settings.llmKind.displayName()} не задан. " +
                "Используйте /api-key <ключ>.",
        )
        clientFactory(settings.llmKind, apiKey)
    }

    suspend fun run(initialPrompt: String? = null) {
        terminal.setTitle(windowTitle())
        try {
            terminal.println("Интерактивный LLM CLI запущен. /help — список команд.")
            initialPrompt?.takeIf(String::isNotBlank)?.let { executePrompt(it) }

            var keepRunning = true
            while (keepRunning) {
                terminal.print("\n${statusLine()}\n> ")
                val input = terminal.readLine() ?: break
                keepRunning = processLine(input)
            }
        } finally {
            terminal.setTitle("")
            terminal.println("\nРабота завершена.")
        }
    }

    internal suspend fun processLine(input: String): Boolean {
        val line = input.trim()
        if (line.isBlank()) return true
        if (!line.startsWith("/")) {
            executePrompt(line)
            return true
        }

        val command = line.substringBefore(" ").lowercase()
        val arguments = line.substringAfter(" ", missingDelimiterValue = "").trim()
        return when (command) {
            "/exit", "/quit" -> false
            "/help" -> showHelp().let { true }
            "/settings" -> showSettings().let { true }
            "/reset" -> resetHistory().let { true }
            "/provider" -> changeProvider(arguments).let { true }
            "/api-key", "/key" -> changeApiKey(arguments).let { true }
            "/mode" -> changeMode(arguments).let { true }
            "/max-tokens" -> changePositiveInt(arguments, "max-tokens") {
                settings.maxTokens = it
            }.let { true }
            "/max-words" -> changePositiveInt(arguments, "max-words") {
                settings.maxWords = it
            }.let { true }
            "/format" -> changeFormat(arguments).let { true }
            "/stop" -> changeStopSequence(arguments).let { true }
            "/history" -> changeHistory(arguments).let { true }
            else -> {
                terminal.println("Неизвестная команда: $command. Используйте /help.")
                true
            }
        }
    }

    private suspend fun executePrompt(prompt: String) {
        try {
            val responses = promptRunner.complete(prompt, settings)
            responses.forEach { response ->
                terminal.println()
                terminal.println("=== ${response.variant.heading} ===")
                terminal.println(response.content)
            }
            terminal.println()
            terminal.println("Результат сравнения")
            terminal.println(renderComparisonTable(responses))
        } catch (error: LlmApiException) {
            terminal.println("Ошибка: ${error.message}")
        } catch (error: MissingApiKeyException) {
            terminal.println("Ошибка: ${error.message}")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            terminal.println("Ошибка выполнения запроса: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun changeProvider(value: String) {
        if (value.isBlank()) {
            terminal.println("Использование: /provider OpenAI|Deepseek")
            return
        }

        val kind = try {
            LlmKind.from(value)
        } catch (error: IllegalArgumentException) {
            terminal.println("Ошибка: ${error.message}")
            return
        }

        settings.llmKind = kind
        terminal.setTitle(windowTitle())
        val keyStatus = if (apiKeys.containsKey(kind)) {
            "ключ настроен"
        } else {
            "ключ не задан; используйте /api-key <ключ>"
        }
        terminal.println("Провайдер: ${kind.displayName()} ($keyStatus). История сохранена.")
    }

    private fun changeApiKey(value: String) {
        if (value.isBlank()) {
            terminal.println("Использование: /api-key <ключ>")
            return
        }

        apiKeys[settings.llmKind] = value
        terminal.println("API-ключ для ${settings.llmKind.displayName()} обновлён.")
    }

    private fun changeMode(value: String) {
        val mode = ResponseMode.from(value)
        if (mode == null) {
            terminal.println("Использование: /mode compare|controlled|unrestricted")
            return
        }

        settings.responseMode = mode
        terminal.println("Режим ответа: ${mode.cliValue}.")
    }

    private fun changePositiveInt(
        value: String,
        parameterName: String,
        update: (Int) -> Unit,
    ) {
        val number = value.toIntOrNull()?.takeIf { it > 0 }
        if (number == null) {
            terminal.println("Использование: /$parameterName <целое число больше нуля>")
            return
        }

        update(number)
        terminal.println("$parameterName: $number.")
    }

    private fun changeFormat(value: String) {
        val parts = value.split(Regex("\\s+")).filter(String::isNotBlank)
        val bulletCount = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        if (parts.size != 2 || !parts[0].equals("bullets", ignoreCase = true) || bulletCount == null) {
            terminal.println("Использование: /format bullets <количество>")
            return
        }

        settings.bulletCount = bulletCount
        terminal.println("Формат: маркированный список. Количество пунктов: $bulletCount.")
    }

    private fun changeStopSequence(value: String) {
        if (value.isBlank()) {
            terminal.println("Использование: /stop <последовательность>|off")
            return
        }

        settings.stopSequence = value.takeUnless { it.equals("off", ignoreCase = true) }
        terminal.println(
            settings.stopSequence?.let { "Условие завершения: $it." }
                ?: "Stop sequence отключена; действует явная инструкция завершения.",
        )
    }

    private fun changeHistory(value: String) {
        when (value.lowercase()) {
            "on" -> settings.historyEnabled = true
            "off" -> settings.historyEnabled = false
            else -> {
                terminal.println("Использование: /history on|off")
                return
            }
        }
        terminal.println("История: ${settings.historyEnabled.onOff()}.")
    }

    private fun resetHistory() {
        promptRunner.clearHistory()
        terminal.println("История обеих веток очищена.")
    }

    private fun showSettings() {
        val turns = promptRunner.historyTurnCounts()
        terminal.println(
            """
            Текущие настройки:
            - Провайдер: ${settings.llmKind.displayName()}
            - API-ключ: ${if (apiKeys.containsKey(settings.llmKind)) "задан" else "не задан"}
            - Режим: ${settings.responseMode.cliValue}
            - Количество пунктов: ${settings.bulletCount}
            - Максимум слов: ${settings.maxWords}
            - Максимум токенов: ${settings.maxTokens}
            - Stop sequence: ${settings.stopSequence ?: "отключена"}
            - История: ${settings.historyEnabled.onOff()}
            - Ходов в истории: unrestricted=${turns.getValue(ResponseVariant.UNRESTRICTED)}, controlled=${turns.getValue(ResponseVariant.CONTROLLED)}
            """.trimIndent(),
        )
    }

    private fun showHelp() {
        terminal.println(
            """
            Команды:
              /provider OpenAI|Deepseek          сменить провайдера
              /api-key <ключ>                    задать ключ текущего провайдера
              /mode compare|controlled|unrestricted
              /max-tokens <число>                задать API-лимит токенов
              /max-words <число>                 задать лимит слов в инструкции
              /format bullets <число>            задать количество пунктов
              /stop <последовательность>|off     изменить условие завершения
              /history on|off                    включить или отключить контекст
              /reset                             очистить историю обеих веток
              /settings                          показать настройки
              /help                              показать эту справку
              /exit                              завершить приложение
            """.trimIndent(),
        )
    }

    private fun statusLine(): String = buildString {
        append("[")
        append(settings.llmKind.displayName())
        append(" | ")
        append(settings.responseMode.cliValue)
        append(" | history=")
        append(settings.historyEnabled.onOff())
        append("]")
    }

    private fun windowTitle(): String = "LLM CLI — ${settings.llmKind.displayName()}"
}

private class MissingApiKeyException(message: String) : RuntimeException(message)

private fun LlmKind.displayName(): String = when (this) {
    LlmKind.DEEPSEEK -> "Deepseek"
    LlmKind.OPENAI -> "OpenAI"
}

private fun Boolean.onOff(): String = if (this) "on" else "off"
