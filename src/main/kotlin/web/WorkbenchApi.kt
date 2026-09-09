package org.example.web

import kotlinx.serialization.json.*
import org.example.app.ResponseMode
import org.example.app.ResponseVariant
import org.example.app.TokenDemoScenario
import org.example.app.WorkbenchController

class ApiProblem(val status: Int, val code: String, override val message: String) : RuntimeException(message)

/** The controller monitor makes checks + mutations atomic, including worker completion. */
class WorkbenchApi(val controller: WorkbenchController) {
    /** Scrub string values, even if a provider unexpectedly echoes a credential as output.
     * Scrubbing JSON values rather than raw JSON preserves escaping and the wire schema. */
    private fun safeJson(value: JsonElement): String = synchronized(controller) {
        fun scrub(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { scrub(it.value) })
            is JsonArray -> JsonArray(value.map { scrub(it) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(controller.redact(value.content)) else value
        }
        apiJson.encodeToString(scrub(value))
    }

    fun snapshot(state: StateDto = controller.state.value.toDto()): String = safeJson(apiJson.encodeToJsonElement(state))

    fun history(): String {
        val state = controller.state.value
        return safeJson(apiJson.encodeToJsonElement(HistoryDetailsDto(
            state.toDto().history,
            ResponseVariant.entries.associate { variant ->
                variant.name.lowercase() to state.historyMessages[variant].orEmpty().map { HistoryMessageDto(it.role.apiValue, it.content) }
            },
            state.toDto().tokenConversations,
        )))
    }

    private val requests = mutableMapOf<String, Pair<StartCommand, Long>>()

    private fun requireIdle() {
        if (controller.state.value.isRunning) throw ApiProblem(409, "busy", "Уже выполняется эксперимент. Дождитесь завершения или отмените его.")
    }
    private fun requireVersion(version: Long) {
        if (controller.state.value.settingsVersion != version) throw ApiProblem(409, "stale_settings", "Настройки изменены в другой вкладке. Они обновлены; повторите действие.")
    }

    fun settings(command: SettingsCommand): StateDto = synchronized(controller) {
        requireIdle()
        requireVersion(command.expectedSettingsVersion)
        val next = command.settings.toSettings()
        val previous = controller.state.value.settings
        if (previous.responseMode == ResponseMode.MODEL_COMPARISON && next.responseMode == previous.responseMode &&
            (next.llmKind != previous.llmKind || next.model != previous.model)) {
            throw ApiProblem(400, "validation", "Выбор подключения заблокирован в сравнении моделей.")
        }
        controller.updateSettings { next }
        controller.state.value.toDto()
    }

    fun key(command: KeyCommand): StateDto = synchronized(controller) {
        requireIdle()
        requireVersion(command.expectedSettingsVersion)
        if (command.provider != controller.state.value.settings.llmKind.name) {
            throw ApiProblem(409, "stale_settings", "Провайдер изменился. Повторите сохранение для выбранного провайдера.")
        }
        controller.saveApiKey(command.key)
        controller.state.value.toDto()
    }

    fun start(command: StartCommand): StartReply = synchronized(controller) {
        if (!command.requestId.matches(Regex("[A-Za-z0-9_-]{8,100}"))) throw ApiProblem(400, "validation", "Некорректный идентификатор команды.")
        requests[command.requestId]?.let { (previous, id) ->
            if (previous != command) throw ApiProblem(409, "duplicate_id", "Этот идентификатор уже использован для другого запроса.")
            return StartReply(id)
        }
        requireIdle()
        requireVersion(command.expectedSettingsVersion)
        if (command.prompt.length > 100_000) throw ApiProblem(400, "validation", "Запрос слишком длинный: максимум 100 000 символов.")
        val accepted = when (command.demo) {
            null -> controller.submit(command.prompt)
            "reasoning" -> controller.submitReasoningDemo()
            "temperature" -> controller.submitTemperatureDemo()
            "tokens-short" -> controller.submitTokenDemo(TokenDemoScenario.SHORT)
            "tokens-long" -> controller.submitTokenDemo(TokenDemoScenario.LONG)
            "tokens-overflow" -> controller.submitTokenDemo(TokenDemoScenario.OVERFLOW)
            else -> throw ApiProblem(400, "validation", "Неизвестная демонстрация.")
        }
        if (!accepted) throw ApiProblem(400, "validation", controller.state.value.notice?.message ?: "Запрос не принят.")
        val id = controller.state.value.exchanges.last().id
        requests[command.requestId] = command to id
        StartReply(id)
    }

    fun cancel(id: Long): StateDto = synchronized(controller) {
        val state = controller.state.value
        if (state.isRunning && state.exchanges.last().id == id) controller.cancelCurrent()
        else if (state.exchanges.none { it.id == id }) throw ApiProblem(404, "not_found", "Операция не найдена.")
        controller.state.value.toDto()
    }

    fun clear(history: Boolean): StateDto = synchronized(controller) {
        requireIdle()
        if (history) {
            if (!controller.clearHistory()) {
                throw ApiProblem(500, "persistence", "Не удалось очистить постоянную историю. Прежняя история сохранена.")
            }
        } else {
            controller.clearResults()
        }
        controller.state.value.toDto()
    }
}
