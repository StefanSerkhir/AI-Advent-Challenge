package org.example.web

import kotlinx.serialization.json.*
import org.example.agent.*
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
            state.toDto().context,
        )))
    }

    fun memory(): String = safeJson(apiJson.encodeToJsonElement(controller.state.value.assistantMemory.toDto()))

    fun profile(): String = safeJson(apiJson.encodeToJsonElement(controller.state.value.assistantMemory.profile.toDto()))

    fun invariants(): String = safeJson(apiJson.encodeToJsonElement(controller.state.value.assistantInvariants.toDto()))

    fun taskState(): String = safeJson(apiJson.encodeToJsonElement(
        TaskStateSnapshotDto(controller.state.value.taskState?.toDto()),
    ))

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
        val state = controller.state.value
        if (command.demo == null && state.settings.responseMode == ResponseMode.UNRESTRICTED &&
            state.settings.contextStrategy == ContextStrategy.MEMORY_LAYERS && state.taskState?.paused == true) {
            throw ApiProblem(409, "task_paused", "Задача приостановлена. Сначала продолжите её в панели состояния задачи.")
        }
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

    fun checkpoint(command: ContextMutationCommand): StateDto = synchronized(controller) {
        requireIdle()
        requireVersion(command.expectedSettingsVersion)
        if (!controller.createCheckpoint()) throw ApiProblem(409, "busy", "Операция уже выполняется.")
        controller.state.value.toDto()
    }

    fun switchBranch(command: SwitchBranchCommand): StateDto = synchronized(controller) {
        requireIdle()
        requireVersion(command.expectedSettingsVersion)
        if (command.branchId.isBlank() || command.branchId.length > 200) throw ApiProblem(400, "validation", "Некорректный ID ветки.")
        try {
            if (!controller.switchBranch(command.branchId)) throw ApiProblem(409, "busy", "Операция уже выполняется.")
        } catch (_: IllegalArgumentException) {
            throw ApiProblem(400, "validation", "Неизвестная ветка или стратегия контекста.")
        }
        controller.state.value.toDto()
    }

    fun addMemory(command: MemoryAddCommand): StateDto = memoryMutation(command.expectedSettingsVersion, command.layer) { layer ->
        controller.addMemory(layer, command.text)
    }

    fun updateMemory(command: MemoryUpdateCommand): StateDto = memoryMutation(command.expectedSettingsVersion, command.layer) { layer ->
        controller.updateMemory(layer, command.id, command.text)
    }

    fun deleteMemory(command: MemoryDeleteCommand): StateDto = memoryMutation(command.expectedSettingsVersion, command.layer) { layer ->
        controller.deleteMemory(layer, command.id)
    }

    fun clearMemory(command: MemoryLayerCommand): StateDto = memoryMutation(command.expectedSettingsVersion, command.layer) { layer ->
        controller.clearMemory(layer)
    }

    fun setMemoryEnabled(command: MemoryEnabledCommand): StateDto = memoryMutation(command.expectedSettingsVersion, command.layer) { layer ->
        controller.setMemoryEnabled(layer, command.enabled)
    }

    fun newDialogue(command: ContextMutationCommand): StateDto = memoryAction(command.expectedSettingsVersion) {
        controller.newAssistantDialogue()
    }

    fun completeTask(command: ContextMutationCommand): StateDto = memoryAction(command.expectedSettingsVersion) {
        controller.completeAssistantTask()
    }

    fun saveProfile(command: AssistantProfileCommand): StateDto = memoryAction(command.expectedSettingsVersion) {
        controller.saveAssistantProfile(command.profile.toDomain())
    }

    fun addInvariant(command: AssistantInvariantAddCommand): StateDto = invariantAction(
        command.expectedSettingsVersion,
        command.category,
    ) { category -> controller.addAssistantInvariant(AssistantInvariantDraft(requireNotNull(category), command.text)) }

    fun updateInvariant(command: AssistantInvariantUpdateCommand): StateDto = invariantAction(
        command.expectedSettingsVersion,
        command.category,
    ) { category -> controller.updateAssistantInvariant(command.id, AssistantInvariantDraft(requireNotNull(category), command.text)) }

    fun deleteInvariant(command: AssistantInvariantDeleteCommand): StateDto = invariantAction(
        command.expectedSettingsVersion,
    ) { controller.deleteAssistantInvariant(command.id) }

    fun startTaskState(command: TaskStateStartCommand): StateDto = taskAction(command.expectedSettingsVersion) {
        controller.startTaskState(NewTaskDraft(command.goal, command.currentStep, command.expectedAction))
    }

    fun updateTaskProgress(command: TaskStateProgressCommand): StateDto = taskAction(command.expectedSettingsVersion) {
        controller.updateTaskProgress(TaskProgressDraft(command.currentStep, command.expectedAction))
    }

    fun advanceTaskState(command: ContextMutationCommand): StateDto = taskAction(command.expectedSettingsVersion) {
        controller.advanceTaskState()
    }

    fun pauseTaskState(command: ContextMutationCommand): StateDto = taskAction(command.expectedSettingsVersion) {
        controller.pauseTaskState()
    }

    fun resumeTaskState(command: ContextMutationCommand): StateDto = taskAction(command.expectedSettingsVersion) {
        controller.resumeTaskState()
    }

    fun resetTaskState(command: ContextMutationCommand): StateDto = taskAction(command.expectedSettingsVersion) {
        controller.resetTaskState()
    }

    private fun memoryMutation(expectedVersion: Long, rawLayer: String, action: (MemoryLayer) -> Boolean): StateDto {
        val layer = MemoryLayer.from(rawLayer)
            ?: throw ApiProblem(400, "validation", "Неизвестный слой памяти.")
        return memoryAction(expectedVersion) { action(layer) }
    }

    private fun memoryAction(expectedVersion: Long, action: () -> Boolean): StateDto = synchronized(controller) {
        requireIdle()
        requireVersion(expectedVersion)
        try {
            if (!action()) throw ApiProblem(500, "persistence", controller.state.value.notice?.message ?: "Не удалось изменить память.")
        } catch (error: IllegalArgumentException) {
            throw ApiProblem(400, "validation", error.message ?: "Некорректная операция с памятью.")
        }
        controller.state.value.toDto()
    }

    private fun taskAction(expectedVersion: Long, action: () -> Boolean): StateDto = synchronized(controller) {
        requireIdle()
        requireVersion(expectedVersion)
        try {
            if (!action()) throw ApiProblem(
                500,
                "persistence",
                controller.state.value.notice?.message ?: "Не удалось изменить состояние задачи.",
            )
        } catch (error: InvalidTaskTransitionException) {
            throw ApiProblem(409, "invalid_task_transition", error.message ?: "Недопустимый переход состояния задачи.")
        } catch (error: IllegalArgumentException) {
            throw ApiProblem(400, "validation", error.message ?: "Некорректное состояние задачи.")
        }
        controller.state.value.toDto()
    }

    private fun invariantAction(
        expectedVersion: Long,
        rawCategory: String? = null,
        action: (AssistantInvariantCategory?) -> Boolean,
    ): StateDto = synchronized(controller) {
        requireIdle()
        requireVersion(expectedVersion)
        val category = rawCategory?.let {
            AssistantInvariantCategory.from(it)
                ?: throw ApiProblem(400, "validation", "Неизвестная категория инварианта.")
        }
        try {
            if (!action(category)) throw ApiProblem(
                500,
                "persistence",
                controller.state.value.notice?.message ?: "Не удалось изменить инварианты ассистента.",
            )
        } catch (error: IllegalArgumentException) {
            throw ApiProblem(400, "validation", error.message ?: "Некорректный инвариант ассистента.")
        }
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
