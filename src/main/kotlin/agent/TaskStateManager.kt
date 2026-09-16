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

enum class TaskPhase { PLANNING, EXECUTION, VALIDATION, DONE }

data class AgentTaskState(
    val id: String,
    val version: Long,
    val goal: String,
    val phase: TaskPhase,
    val currentStep: String,
    val expectedAction: String,
    val paused: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class NewTaskDraft(
    val goal: String,
    val currentStep: String,
    val expectedAction: String,
)

data class TaskProgressDraft(
    val currentStep: String,
    val expectedAction: String,
)

data class TaskStateDiagnostics(
    val applied: Boolean,
    val taskId: String? = null,
    val stateVersion: Long? = null,
    val phase: TaskPhase? = null,
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
        require(document.formatVersion == TASK_STATE_FORMAT_VERSION) {
            "Unsupported task state format version"
        }
        return document.task?.toDomain()?.also(::validateStoredTask)
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
        if (current?.phase?.let { it != TaskPhase.DONE } == true) {
            throw InvalidTaskTransitionException("Сначала завершите или сбросьте текущую задачу.")
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
        if (task.phase == TaskPhase.DONE) {
            throw InvalidTaskTransitionException("Завершённую задачу нельзя изменять.")
        }
        val candidate = task.nextVersion(
            currentStep = validateUserField("Текущий шаг", draft.currentStep, MAX_TASK_STEP_LENGTH),
            expectedAction = validateUserField("Ожидаемое действие", draft.expectedAction, MAX_TASK_EXPECTED_ACTION_LENGTH),
        )
        validateSecrets(candidate)
        persist(candidate)
        return candidate
    }

    @Synchronized
    fun advance(): AgentTaskState {
        val task = requireCurrent()
        val target = when (task.phase) {
            TaskPhase.PLANNING -> TaskPhase.EXECUTION
            TaskPhase.EXECUTION -> TaskPhase.VALIDATION
            TaskPhase.VALIDATION -> TaskPhase.DONE
            TaskPhase.DONE -> throw InvalidTaskTransitionException("Задача уже завершена.")
        }
        return transitionTo(target)
    }

    @Synchronized
    fun transitionTo(target: TaskPhase): AgentTaskState {
        val task = requireCurrent()
        if (task.paused) throw InvalidTaskTransitionException("Сначала продолжите приостановленную задачу.")
        val allowed = when (task.phase) {
            TaskPhase.PLANNING -> TaskPhase.EXECUTION
            TaskPhase.EXECUTION -> TaskPhase.VALIDATION
            TaskPhase.VALIDATION -> TaskPhase.DONE
            TaskPhase.DONE -> null
        }
        if (target != allowed) {
            throw InvalidTaskTransitionException("Недопустимый переход ${task.phase.name} → ${target.name}.")
        }
        val candidate = task.nextVersion(phase = target, paused = false)
        persist(candidate)
        return candidate
    }

    @Synchronized
    fun pause(): AgentTaskState {
        val task = requireCurrent()
        if (task.phase == TaskPhase.DONE) throw InvalidTaskTransitionException("Завершённую задачу нельзя поставить на паузу.")
        if (task.paused) throw InvalidTaskTransitionException("Задача уже приостановлена.")
        val candidate = task.nextVersion(paused = true)
        persist(candidate)
        return candidate
    }

    @Synchronized
    fun resume(): AgentTaskState {
        val task = requireCurrent()
        if (task.phase == TaskPhase.DONE) throw InvalidTaskTransitionException("Завершённую задачу нельзя продолжить.")
        if (!task.paused) throw InvalidTaskTransitionException("Задача уже активна.")
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

    private fun AgentTaskState.nextVersion(
        phase: TaskPhase = this.phase,
        currentStep: String = this.currentStep,
        expectedAction: String = this.expectedAction,
        paused: Boolean = this.paused,
    ): AgentTaskState = copy(
        version = version + 1,
        phase = phase,
        currentStep = currentStep,
        expectedAction = expectedAction,
        paused = paused,
        updatedAtEpochMillis = maxOf(clock(), updatedAtEpochMillis),
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

private fun AgentTaskState.userValues() = listOf(goal, currentStep, expectedAction)

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
    require(task.createdAtEpochMillis >= 0 && task.updatedAtEpochMillis >= task.createdAtEpochMillis) {
        "Invalid task timestamps"
    }
    require(task.phase != TaskPhase.DONE || !task.paused) { "A completed task cannot be paused" }
}

private const val TASK_STATE_FORMAT_VERSION = 1

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
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun toDomain() = AgentTaskState(
        id = id,
        version = version,
        goal = goal,
        phase = runCatching { TaskPhase.valueOf(phase) }.getOrElse { throw IllegalArgumentException("Unknown task phase") },
        currentStep = currentStep,
        expectedAction = expectedAction,
        paused = paused,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    companion object {
        fun fromDomain(task: AgentTaskState) = StoredTaskState(
            task.id,
            task.version,
            task.goal,
            task.phase.name,
            task.currentStep,
            task.expectedAction,
            task.paused,
            task.createdAtEpochMillis,
            task.updatedAtEpochMillis,
        )
    }
}
