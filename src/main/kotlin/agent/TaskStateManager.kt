package org.example.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

const val DEFAULT_TASK_STATE_FILE_NAME = ".llm-task-state.json"
const val MAX_TASK_GOAL_LENGTH = 8_000
const val MAX_TASK_STEP_LENGTH = 4_000
const val MAX_TASK_EXPECTED_ACTION_LENGTH = 4_000
const val MAX_TASK_VALIDATION_DETAILS_LENGTH = 4_000

enum class TaskPhase { PLANNING, EXECUTION, VALIDATION, DONE }
enum class TaskValidationStatus { NOT_RUN, FAILED, PASSED }
enum class TaskTransitionAction { APPROVE_PLAN, COMPLETE_IMPLEMENTATION, CONFIRM_VALIDATION_SUCCESS }
enum class TaskAvailableAction {
    UPDATE_PROGRESS,
    APPROVE_PLAN,
    COMPLETE_IMPLEMENTATION,
    RECORD_VALIDATION_FAILURE,
    CONFIRM_VALIDATION_SUCCESS,
    PAUSE,
    RESUME,
    RESET,
    START_NEW,
}

data class TaskTransitionRule(
    val from: TaskPhase,
    val action: TaskTransitionAction,
    val to: TaskPhase,
)

/** The single domain source of truth for every phase-changing command. */
val TASK_TRANSITION_RULES: Map<TaskPhase, TaskTransitionRule> = listOf(
    TaskTransitionRule(TaskPhase.PLANNING, TaskTransitionAction.APPROVE_PLAN, TaskPhase.EXECUTION),
    TaskTransitionRule(TaskPhase.EXECUTION, TaskTransitionAction.COMPLETE_IMPLEMENTATION, TaskPhase.VALIDATION),
    TaskTransitionRule(TaskPhase.VALIDATION, TaskTransitionAction.CONFIRM_VALIDATION_SUCCESS, TaskPhase.DONE),
).associateBy(TaskTransitionRule::from)

data class AgentTaskState(
    val id: String,
    val version: Long,
    val goal: String,
    val phase: TaskPhase,
    val currentStep: String,
    val expectedAction: String,
    val paused: Boolean,
    val planApprovedAtEpochMillis: Long?,
    val implementationCompletedAtEpochMillis: Long?,
    val validationStatus: TaskValidationStatus,
    val validationDetails: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun availableActions(): Set<TaskAvailableAction> = when {
        phase == TaskPhase.DONE -> setOf(TaskAvailableAction.START_NEW, TaskAvailableAction.RESET)
        paused -> setOf(TaskAvailableAction.RESUME, TaskAvailableAction.RESET)
        else -> buildSet {
            add(TaskAvailableAction.UPDATE_PROGRESS)
            add(TaskAvailableAction.PAUSE)
            add(TaskAvailableAction.RESET)
            when (TASK_TRANSITION_RULES.getValue(phase).action) {
                TaskTransitionAction.APPROVE_PLAN -> add(TaskAvailableAction.APPROVE_PLAN)
                TaskTransitionAction.COMPLETE_IMPLEMENTATION -> add(TaskAvailableAction.COMPLETE_IMPLEMENTATION)
                TaskTransitionAction.CONFIRM_VALIDATION_SUCCESS -> {
                    add(TaskAvailableAction.RECORD_VALIDATION_FAILURE)
                    add(TaskAvailableAction.CONFIRM_VALIDATION_SUCCESS)
                }
            }
        }
    }
}

data class NewTaskDraft(
    val goal: String,
    val currentStep: String,
    val expectedAction: String,
)

data class TaskProgressDraft(
    val currentStep: String,
    val expectedAction: String,
)

data class TaskValidationDraft(
    val successful: Boolean,
    val details: String,
    val expectedAction: String? = null,
)

data class TaskStateDiagnostics(
    val applied: Boolean,
    val taskId: String? = null,
    val stateVersion: Long? = null,
    val phase: TaskPhase? = null,
    val responseBlocked: Boolean = false,
)

interface TaskStateStore {
    fun load(): AgentTaskState?
    fun save(state: AgentTaskState?)
}

class InMemoryTaskStateStore(initial: AgentTaskState? = null) : TaskStateStore {
    private var state = initial

    @Synchronized
    override fun load(): AgentTaskState? = state

    @Synchronized
    override fun save(state: AgentTaskState?) {
        this.state = state
    }
}

class TaskStatePersistenceException(cause: Exception) :
    IOException("Не удалось сохранить состояние задачи.", cause)

class InvalidTaskTransitionException(message: String) : IllegalStateException(message)

class JsonTaskStateStore(
    private val file: Path = Path.of(DEFAULT_TASK_STATE_FILE_NAME),
) : TaskStateStore {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    @Synchronized
    override fun load(): AgentTaskState? {
        if (!Files.exists(file)) return null
        val document = json.decodeFromString<StoredTaskStateDocument>(
            Files.readString(file, StandardCharsets.UTF_8),
        )
        require(document.formatVersion == 1 || document.formatVersion == TASK_STATE_FORMAT_VERSION) {
            "Unsupported task state format version"
        }
        return document.task?.toDomain(document.formatVersion)?.also(::validateStoredTask)
    }

    @Synchronized
    override fun save(state: AgentTaskState?) {
        state?.also(::validateStoredTask)
        val target = file.toAbsolutePath()
        try {
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, ".llm-task-state-", ".tmp")
            try {
                Files.writeString(
                    temporary,
                    json.encodeToString(StoredTaskStateDocument.fromDomain(state)),
                    StandardCharsets.UTF_8,
                )
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (error: Exception) {
            if (error is TaskStatePersistenceException) throw error
            throw TaskStatePersistenceException(error)
        }
    }
}

class TaskStateManager(
    private val store: TaskStateStore = InMemoryTaskStateStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val sensitiveText: (String) -> Boolean = { false },
) {
    val loadWarning: String?
    private var current: AgentTaskState?

    init {
        val loaded = runCatching {
            store.load()?.also { task ->
                task.userValues().forEach { value ->
                    require(!sensitiveText(value)) { "Состояние задачи содержит настроенный API-ключ." }
                }
            }
        }
        current = loaded.getOrNull()
        loadWarning = loaded.exceptionOrNull()?.let {
            "Не удалось восстановить состояние задачи: файл повреждён, недоступен или имеет неподдерживаемую версию. Используется пустое состояние."
        }
    }

    @Synchronized
    fun state(): AgentTaskState? = current

    @Synchronized
    fun containsSensitiveValue(value: String): Boolean = current?.userValues()?.any { value in it } == true

    @Synchronized
    fun start(draft: NewTaskDraft): AgentTaskState {
        current?.takeIf { it.phase != TaskPhase.DONE }?.let {
            invalid(it, "сначала завершите или сбросьте текущую задачу")
        }
        val now = clock().also { require(it >= 0) { "Некорректное время создания задачи." } }
        val candidate = AgentTaskState(
            id = validateTaskId(idFactory()),
            version = 1,
            goal = validateUserField("Цель", draft.goal, MAX_TASK_GOAL_LENGTH),
            phase = TaskPhase.PLANNING,
            currentStep = validateUserField("Текущий шаг", draft.currentStep, MAX_TASK_STEP_LENGTH),
            expectedAction = validateUserField("Ожидаемое действие", draft.expectedAction, MAX_TASK_EXPECTED_ACTION_LENGTH),
            paused = false,
            planApprovedAtEpochMillis = null,
            implementationCompletedAtEpochMillis = null,
            validationStatus = TaskValidationStatus.NOT_RUN,
            validationDetails = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        validateSecrets(candidate)
        persist(candidate)
        return candidate
    }

    @Synchronized
    fun updateProgress(draft: TaskProgressDraft): AgentTaskState {
        val task = requireCurrent()
        ensureActive(task)
        if (task.phase == TaskPhase.DONE) invalid(task, "завершённую задачу нельзя изменять")
        val candidate = task.nextVersion(
            currentStep = validateUserField("Текущий шаг", draft.currentStep, MAX_TASK_STEP_LENGTH),
            expectedAction = validateUserField("Ожидаемое действие", draft.expectedAction, MAX_TASK_EXPECTED_ACTION_LENGTH),
        )
        validateSecrets(candidate)
        persist(candidate)
        return candidate
    }

    @Synchronized
    fun approvePlan(): AgentTaskState = applyTransition(TaskTransitionAction.APPROVE_PLAN) { task, now ->
        task.copy(planApprovedAtEpochMillis = now)
    }

    @Synchronized
    fun completeImplementation(): AgentTaskState = applyTransition(TaskTransitionAction.COMPLETE_IMPLEMENTATION) { task, now ->
        task.copy(implementationCompletedAtEpochMillis = now)
    }

    @Synchronized
    fun recordValidation(draft: TaskValidationDraft): AgentTaskState {
        val task = requireCurrent()
        ensureActive(task)
        if (task.phase != TaskPhase.VALIDATION || task.implementationCompletedAtEpochMillis == null) {
            invalid(task, "результат проверки можно фиксировать только после завершения реализации")
        }
        val details = validateUserField(
            if (draft.successful) "Результат успешной проверки" else "Причина неуспешной проверки",
            draft.details,
            MAX_TASK_VALIDATION_DETAILS_LENGTH,
        )
        if (draft.successful) {
            return applyTransition(TaskTransitionAction.CONFIRM_VALIDATION_SUCCESS) { candidate, _ ->
                candidate.copy(validationStatus = TaskValidationStatus.PASSED, validationDetails = details)
            }
        }
        val nextAction = validateUserField(
            "Следующее действие после неуспешной проверки",
            draft.expectedAction.orEmpty(),
            MAX_TASK_EXPECTED_ACTION_LENGTH,
        )
        val candidate = task.nextVersion(
            expectedAction = nextAction,
            validationStatus = TaskValidationStatus.FAILED,
            validationDetails = details,
        )
        validateSecrets(candidate)
        persist(candidate)
        return candidate
    }

    /** Direct phase changes cannot establish the explicit evidence required by the lifecycle. */
    @Synchronized
    fun transitionTo(target: TaskPhase): AgentTaskState {
        val task = requireCurrent()
        ensureActive(task)
        val rule = TASK_TRANSITION_RULES[task.phase]
        if (rule == null || rule.to != target) invalid(task, "переход ${task.phase.name} → ${target.name} запрещён")
        invalid(task, "переход требует явной команды ${rule.action.name}")
    }

    @Synchronized
    fun pause(): AgentTaskState {
        val task = requireCurrent()
        if (task.phase == TaskPhase.DONE) invalid(task, "завершённую задачу нельзя поставить на паузу")
        if (task.paused) invalid(task, "задача уже приостановлена")
        val candidate = task.nextVersion(paused = true)
        persist(candidate)
        return candidate
    }

    @Synchronized
    fun resume(): AgentTaskState {
        val task = requireCurrent()
        if (task.phase == TaskPhase.DONE) invalid(task, "завершённую задачу нельзя продолжить")
        if (!task.paused) invalid(task, "задача уже активна")
        val candidate = task.nextVersion(paused = false)
        persist(candidate)
        return candidate
    }

    @Synchronized
    fun reset() {
        if (current == null) throw InvalidTaskTransitionException("Состояние задачи уже пусто.")
        persist(null)
    }

    private fun requireCurrent(): AgentTaskState = current
        ?: throw InvalidTaskTransitionException("Сначала создайте задачу.")

    private fun ensureActive(task: AgentTaskState) {
        if (task.paused) invalid(task, "задача приостановлена")
    }

    private fun invalid(task: AgentTaskState, reason: String): Nothing {
        val next = when {
            task.paused -> "RESUME"
            task.phase == TaskPhase.DONE -> "создать новую задачу или выполнить RESET"
            else -> TASK_TRANSITION_RULES.getValue(task.phase).action.name
        }
        throw InvalidTaskTransitionException(
            "Недопустимое действие: $reason. Текущая фаза: ${task.phase.name}. Допустимое следующее действие: $next.",
        )
    }

    private fun applyTransition(
        action: TaskTransitionAction,
        recordEvidence: (AgentTaskState, Long) -> AgentTaskState,
    ): AgentTaskState {
        val task = requireCurrent()
        ensureActive(task)
        val rule = TASK_TRANSITION_RULES[task.phase]
        if (rule == null || rule.action != action) invalid(task, "команда ${action.name} сейчас запрещена")
        val now = maxOf(clock(), task.updatedAtEpochMillis)
        val withEvidence = recordEvidence(task, now)
        val candidate = withEvidence.nextVersion(phase = rule.to, paused = false, now = now)
        validateTransitionEvidence(candidate)
        validateSecrets(candidate)
        persist(candidate)
        return candidate
    }

    private fun AgentTaskState.nextVersion(
        phase: TaskPhase = this.phase,
        currentStep: String = this.currentStep,
        expectedAction: String = this.expectedAction,
        paused: Boolean = this.paused,
        validationStatus: TaskValidationStatus = this.validationStatus,
        validationDetails: String? = this.validationDetails,
        now: Long = maxOf(clock(), updatedAtEpochMillis),
    ): AgentTaskState = copy(
        version = version + 1,
        phase = phase,
        currentStep = currentStep,
        expectedAction = expectedAction,
        paused = paused,
        validationStatus = validationStatus,
        validationDetails = validationDetails,
        updatedAtEpochMillis = now,
    )

    private fun validateSecrets(task: AgentTaskState) {
        task.userValues().forEach { value ->
            require(!sensitiveText(value)) { "Состояние задачи не может содержать настроенный API-ключ." }
        }
    }

    private fun persist(candidate: AgentTaskState?) {
        try {
            store.save(candidate)
        } catch (error: Exception) {
            if (error is TaskStatePersistenceException) throw error
            throw TaskStatePersistenceException(error)
        }
        current = candidate
    }
}

private fun AgentTaskState.userValues() = listOfNotNull(goal, currentStep, expectedAction, validationDetails)

private fun validateTaskId(value: String): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= 200 && it.none(Char::isISOControl)) { "Некорректный ID задачи." }
}

private fun validateUserField(label: String, value: String, maxLength: Int): String {
    val normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim()
    require(normalized.isNotEmpty()) { "$label не может быть пустым." }
    require(normalized.length <= maxLength) { "$label: максимум $maxLength символов." }
    require(normalized.none { it.isISOControl() && it != '\n' && it != '\t' }) {
        "$label содержит недопустимые управляющие символы."
    }
    return normalized
}

private fun validateStoredTask(task: AgentTaskState) {
    validateTaskId(task.id)
    require(task.version >= 1) { "Invalid task state version" }
    require(validateUserField("Цель", task.goal, MAX_TASK_GOAL_LENGTH) == task.goal) { "Invalid stored task goal" }
    require(validateUserField("Текущий шаг", task.currentStep, MAX_TASK_STEP_LENGTH) == task.currentStep) { "Invalid stored task step" }
    require(validateUserField("Ожидаемое действие", task.expectedAction, MAX_TASK_EXPECTED_ACTION_LENGTH) == task.expectedAction) {
        "Invalid stored expected action"
    }
    task.validationDetails?.let {
        require(validateUserField("Результат проверки", it, MAX_TASK_VALIDATION_DETAILS_LENGTH) == it) {
            "Invalid stored validation details"
        }
    }
    require(task.createdAtEpochMillis >= 0 && task.updatedAtEpochMillis >= task.createdAtEpochMillis) {
        "Invalid task timestamps"
    }
    require(task.phase != TaskPhase.DONE || !task.paused) { "A completed task cannot be paused" }
    validateTransitionEvidence(task)
}

private fun validateTransitionEvidence(task: AgentTaskState) {
    listOfNotNull(task.planApprovedAtEpochMillis, task.implementationCompletedAtEpochMillis).forEach {
        require(it in task.createdAtEpochMillis..task.updatedAtEpochMillis) { "Invalid task transition timestamp" }
    }
    when (task.phase) {
        TaskPhase.PLANNING -> require(task.planApprovedAtEpochMillis == null &&
            task.implementationCompletedAtEpochMillis == null &&
            task.validationStatus == TaskValidationStatus.NOT_RUN && task.validationDetails == null)
        TaskPhase.EXECUTION -> require(task.planApprovedAtEpochMillis != null &&
            task.implementationCompletedAtEpochMillis == null &&
            task.validationStatus == TaskValidationStatus.NOT_RUN && task.validationDetails == null)
        TaskPhase.VALIDATION -> require(task.planApprovedAtEpochMillis != null &&
            task.implementationCompletedAtEpochMillis != null &&
            task.validationStatus != TaskValidationStatus.PASSED)
        TaskPhase.DONE -> require(task.planApprovedAtEpochMillis != null &&
            task.implementationCompletedAtEpochMillis != null &&
            task.validationStatus == TaskValidationStatus.PASSED && task.validationDetails != null)
    }
}

private const val TASK_STATE_FORMAT_VERSION = 2

@Serializable
private data class StoredTaskStateDocument(
    val formatVersion: Int = TASK_STATE_FORMAT_VERSION,
    val task: StoredTaskState? = null,
) {
    companion object {
        fun fromDomain(task: AgentTaskState?) = StoredTaskStateDocument(task = task?.let(StoredTaskState::fromDomain))
    }
}

@Serializable
private data class StoredTaskState(
    val id: String,
    val version: Long,
    val goal: String,
    val phase: String,
    val currentStep: String,
    val expectedAction: String,
    val paused: Boolean,
    val planApprovedAtEpochMillis: Long? = null,
    val implementationCompletedAtEpochMillis: Long? = null,
    val validationStatus: String = TaskValidationStatus.NOT_RUN.name,
    val validationDetails: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun toDomain(formatVersion: Int): AgentTaskState {
        val parsedPhase = runCatching { TaskPhase.valueOf(phase) }
            .getOrElse { throw IllegalArgumentException("Unknown task phase") }
        val migratedPlanApproval = if (formatVersion == 1 && parsedPhase != TaskPhase.PLANNING) {
            updatedAtEpochMillis
        } else planApprovedAtEpochMillis
        val migratedImplementation = if (formatVersion == 1 && parsedPhase in setOf(TaskPhase.VALIDATION, TaskPhase.DONE)) {
            updatedAtEpochMillis
        } else implementationCompletedAtEpochMillis
        val migratedValidationStatus = if (formatVersion == 1 && parsedPhase == TaskPhase.DONE) {
            TaskValidationStatus.PASSED
        } else runCatching { TaskValidationStatus.valueOf(validationStatus) }
            .getOrElse { throw IllegalArgumentException("Unknown validation status") }
        val migratedDetails = if (formatVersion == 1 && parsedPhase == TaskPhase.DONE) {
            "Мигрировано из завершённого состояния v1."
        } else validationDetails
        return AgentTaskState(
            id = id,
            version = version,
            goal = goal,
            phase = parsedPhase,
            currentStep = currentStep,
            expectedAction = expectedAction,
            paused = paused,
            planApprovedAtEpochMillis = migratedPlanApproval,
            implementationCompletedAtEpochMillis = migratedImplementation,
            validationStatus = migratedValidationStatus,
            validationDetails = migratedDetails,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    companion object {
        fun fromDomain(task: AgentTaskState) = StoredTaskState(
            task.id,
            task.version,
            task.goal,
            task.phase.name,
            task.currentStep,
            task.expectedAction,
            task.paused,
            task.planApprovedAtEpochMillis,
            task.implementationCompletedAtEpochMillis,
            task.validationStatus.name,
            task.validationDetails,
            task.createdAtEpochMillis,
            task.updatedAtEpochMillis,
        )
    }
}
