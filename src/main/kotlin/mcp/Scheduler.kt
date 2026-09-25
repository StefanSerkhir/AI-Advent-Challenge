package org.example.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.*

const val DEFAULT_SCHEDULER_STATE_FILE_NAME = ".llm-scheduler-state.json"
const val SCHEDULER_HISTORY_LIMIT = 100
const val MAX_ACTIVE_SCHEDULES = 50
const val MIN_INTERVAL_SECONDS = 1L
const val MAX_INTERVAL_SECONDS = 31L * 24 * 60 * 60

enum class ScheduleType { ONCE, FIXED_INTERVAL }
enum class ScheduledTaskType { REMINDER, TRACKER_SNAPSHOT }
enum class ScheduleStatus { ACTIVE, COMPLETED, FAILED, CANCELLED }

data class SchedulerCreateCommand(
    val requestId: String,
    val title: String,
    val scheduleType: ScheduleType,
    val runAt: Instant? = null,
    val everySeconds: Long? = null,
    val startAt: Instant? = null,
    val taskType: ScheduledTaskType,
    val reminderText: String? = null,
    val issueId: String? = null,
)

data class ScheduledExecution(
    val id: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val success: Boolean,
    val result: JsonObject? = null,
    val error: String? = null,
)

data class ScheduledTask(
    val id: String,
    val requestId: String,
    val title: String,
    val scheduleType: ScheduleType,
    val runAt: Instant?,
    val startAt: Instant?,
    val everySeconds: Long?,
    val taskType: ScheduledTaskType,
    val reminderText: String?,
    val issueId: String?,
    val status: ScheduleStatus,
    val createdAt: Instant,
    val lastRunAt: Instant? = null,
    val nextRunAt: Instant? = null,
    val totalRuns: Int = 0,
    val successfulRuns: Int = 0,
    val failedRuns: Int = 0,
    val snapshotCount: Int = 0,
    val latestTrackerStatus: String? = null,
    val latestTrackerNextAction: String? = null,
    val statusChanges: Int = 0,
    val nextActionChanges: Int = 0,
    val executions: List<ScheduledExecution> = emptyList(),
)

data class SchedulerState(val schedules: List<ScheduledTask> = emptyList())

data class SchedulerSummary(
    val id: String,
    val title: String,
    val taskType: ScheduledTaskType,
    val status: ScheduleStatus,
    val scheduleType: ScheduleType,
    val aggregationPeriod: String,
    val totalRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val lastRunAt: Instant?,
    val nextRunAt: Instant?,
    val lastResult: String?,
    val lastError: String?,
    val snapshotCount: Int,
    val latestTrackerStatus: String?,
    val latestTrackerNextAction: String?,
    val statusChanges: Int,
    val nextActionChanges: Int,
    val summary: String,
)

data class SchedulerSnapshot(
    val available: Boolean = true,
    val error: String? = null,
    val schedules: List<SchedulerSummary> = emptyList(),
) {
    companion object {
        fun unavailable() = SchedulerSnapshot(
            available = false,
            error = "Локальный MCP-планировщик временно недоступен; выполняется переподключение.",
        )
    }
}

interface SchedulerStore {
    fun load(): SchedulerState
    fun save(state: SchedulerState)
}

class InMemorySchedulerStore(initial: SchedulerState = SchedulerState()) : SchedulerStore {
    private var state = initial
    @Synchronized override fun load(): SchedulerState = state
    @Synchronized override fun save(state: SchedulerState) { this.state = state }
}

class SchedulerPersistenceException(cause: Exception) :
    IOException("Не удалось сохранить состояние планировщика.", cause)

class JsonSchedulerStore(
    private val file: Path = Path.of(DEFAULT_SCHEDULER_STATE_FILE_NAME),
) : SchedulerStore {
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    @Synchronized
    override fun load(): SchedulerState {
        if (!Files.exists(file)) return SchedulerState()
        val document = json.decodeFromString<StoredSchedulerDocument>(
            Files.readString(file, StandardCharsets.UTF_8),
        )
        require(document.formatVersion == SCHEDULER_FORMAT_VERSION) {
            "Unsupported scheduler state format version"
        }
        return document.toDomain().also(::validateStoredSchedulerState)
    }

    @Synchronized
    override fun save(state: SchedulerState) {
        validateStoredSchedulerState(state)
        val target = file.toAbsolutePath()
        try {
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, ".llm-scheduler-state-", ".tmp")
            try {
                Files.writeString(
                    temporary,
                    json.encodeToString(StoredSchedulerDocument.fromDomain(state)),
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
            if (error is SchedulerPersistenceException) throw error
            throw SchedulerPersistenceException(error)
        }
    }
}

data class TrackerIssue(
    val id: String,
    val title: String,
    val status: String,
    val assignee: String,
    val description: String,
    val nextAction: String,
)

fun interface TrackerSource {
    suspend fun getIssue(issueId: String): TrackerIssue
}

class LocalDemoTrackerSource : TrackerSource {
    override suspend fun getIssue(issueId: String): TrackerIssue {
        require(issueId == DEMO_ISSUE_ID) { "Tracker issue '$issueId' was not found." }
        return demoTrackerIssue()
    }
}

fun interface ScheduledTaskExecutor {
    suspend fun execute(task: ScheduledTask): JsonObject
}

class LocalScheduledTaskExecutor(private val trackerSource: TrackerSource) : ScheduledTaskExecutor {
    override suspend fun execute(task: ScheduledTask): JsonObject = when (task.taskType) {
        ScheduledTaskType.REMINDER -> buildJsonObject {
            put("taskType", "reminder")
            put("text", requireNotNull(task.reminderText))
        }
        ScheduledTaskType.TRACKER_SNAPSHOT -> trackerSource.getIssue(requireNotNull(task.issueId)).toJson()
    }
}

/** Event-driven coroutine scheduler. Mutations are persisted before becoming visible. */
class SchedulerService(
    private val store: SchedulerStore,
    private val executor: ScheduledTaskExecutor,
    private val clock: Clock = Clock.systemUTC(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    val loadWarning: String?
    private val mutex = Mutex()
    private val inFlight = mutableSetOf<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private var loopJob: Job? = null
    private var current: SchedulerState

    init {
        val loaded = runCatching { store.load().also(::validateStoredSchedulerState) }
        current = loaded.getOrDefault(SchedulerState())
        loadWarning = loaded.exceptionOrNull()?.let {
            "Не удалось восстановить расписания: файл повреждён, недоступен или имеет неподдерживаемую версию. Используется пустое состояние."
        }
    }

    @Synchronized
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                try {
                    runDue(clock.instant())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed run/persistence attempt is isolated; a mutation or the next due time wakes the loop again.
                    withTimeoutOrNull(1_000) { wake.receive() }
                    continue
                }
                val waitMillis = mutex.withLock {
                    current.schedules.asSequence()
                        .filter { it.status == ScheduleStatus.ACTIVE && it.id !in inFlight }
                        .mapNotNull(ScheduledTask::nextRunAt)
                        .minOrNull()
                        ?.let { next -> (next.toEpochMilli() - clock.instant().toEpochMilli()).coerceAtLeast(1L) }
                        ?: Long.MAX_VALUE
                }
                if (waitMillis == Long.MAX_VALUE) {
                    wake.receive()
                } else {
                    withTimeoutOrNull(waitMillis) { wake.receive() }
                }
            }
        }
    }

    suspend fun create(command: SchedulerCreateCommand): ScheduledTask {
        val normalized = validateCreateCommand(command, clock.instant())
        return mutex.withLock {
            current.schedules.firstOrNull { it.requestId == normalized.requestId }?.let { existing ->
                require(existing.matches(normalized)) {
                    "requestId уже использован для другого расписания."
                }
                return@withLock existing
            }
            require(current.schedules.count { it.status == ScheduleStatus.ACTIVE } < MAX_ACTIVE_SCHEDULES) {
                "Достигнут лимит активных расписаний: $MAX_ACTIVE_SCHEDULES."
            }
            val now = clock.instant()
            val firstRun = when (normalized.scheduleType) {
                ScheduleType.ONCE -> requireNotNull(normalized.runAt)
                ScheduleType.FIXED_INTERVAL -> normalized.startAt ?: now.plusSeconds(requireNotNull(normalized.everySeconds))
            }
            val schedule = ScheduledTask(
                id = idFactory(),
                requestId = normalized.requestId,
                title = normalized.title,
                scheduleType = normalized.scheduleType,
                runAt = normalized.runAt,
                startAt = normalized.startAt,
                everySeconds = normalized.everySeconds,
                taskType = normalized.taskType,
                reminderText = normalized.reminderText,
                issueId = normalized.issueId,
                status = ScheduleStatus.ACTIVE,
                createdAt = now,
                nextRunAt = firstRun,
            )
            persistLocked(current.copy(schedules = current.schedules + schedule))
            wake.trySend(Unit)
            schedule
        }
    }

    suspend fun cancel(id: String): ScheduledTask = mutex.withLock {
        val normalized = validatedId(id, "scheduleId")
        val existing = current.schedules.firstOrNull { it.id == normalized }
            ?: throw IllegalArgumentException("Расписание не найдено.")
        if (existing.status != ScheduleStatus.ACTIVE) return@withLock existing
        val cancelled = existing.copy(status = ScheduleStatus.CANCELLED, nextRunAt = null)
        persistLocked(current.copy(schedules = current.schedules.map { if (it.id == normalized) cancelled else it }))
        wake.trySend(Unit)
        cancelled
    }

    suspend fun summaries(id: String? = null): SchedulerSnapshot = mutex.withLock {
        val selected = if (id == null) {
            current.schedules
        } else {
            val normalized = validatedId(id, "scheduleId")
            listOf(current.schedules.firstOrNull { it.id == normalized }
                ?: throw IllegalArgumentException("Расписание не найдено."))
        }
        SchedulerSnapshot(
            error = loadWarning,
            schedules = selected.sortedBy { it.createdAt }.map(::summarize),
        )
    }

    /** Runs every schedule due at [now] at most once. Safe to call concurrently in deterministic tests. */
    suspend fun runDue(now: Instant = clock.instant()): Int {
        var runs = 0
        while (true) {
            val due = mutex.withLock {
                current.schedules
                    .filter { it.status == ScheduleStatus.ACTIVE && it.id !in inFlight }
                    .filter { it.nextRunAt?.let { dueAt -> !dueAt.isAfter(now) } == true }
                    .minByOrNull { requireNotNull(it.nextRunAt) }
                    ?.also { inFlight += it.id }
            } ?: return runs
            val startedAt = clock.instant()
            val outcome = try {
                Result.success(executor.execute(due))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { mutex.withLock { inFlight -= due.id } }
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            val completedAt = clock.instant()
            val execution = ScheduledExecution(
                id = idFactory(),
                startedAt = startedAt,
                completedAt = completedAt,
                success = outcome.isSuccess,
                result = outcome.getOrNull(),
                error = outcome.exceptionOrNull()?.let(::safeExecutionError),
            )
            try {
                mutex.withLock {
                    val latest = current.schedules.first { it.id == due.id }
                    val periodic = latest.scheduleType == ScheduleType.FIXED_INTERVAL
                    val nextStatus = when {
                        latest.status == ScheduleStatus.CANCELLED -> ScheduleStatus.CANCELLED
                        periodic -> ScheduleStatus.ACTIVE
                        execution.success -> ScheduleStatus.COMPLETED
                        else -> ScheduleStatus.FAILED
                    }
                    val nextRun = if (periodic && nextStatus == ScheduleStatus.ACTIVE) {
                        maxOf(now, completedAt).plusSeconds(requireNotNull(latest.everySeconds))
                    } else null
                    val updated = latest.copy(
                        status = nextStatus,
                        lastRunAt = completedAt,
                        nextRunAt = nextRun,
                        totalRuns = latest.totalRuns + 1,
                        successfulRuns = latest.successfulRuns + if (execution.success) 1 else 0,
                        failedRuns = latest.failedRuns + if (execution.success) 0 else 1,
                        snapshotCount = latest.snapshotCount + if (execution.isTrackerSnapshot()) 1 else 0,
                        latestTrackerStatus = execution.trackerField("status") ?: latest.latestTrackerStatus,
                        latestTrackerNextAction = execution.trackerField("nextAction") ?: latest.latestTrackerNextAction,
                        statusChanges = latest.statusChanges + if (execution.changedTrackerField(latest, "status")) 1 else 0,
                        nextActionChanges = latest.nextActionChanges + if (execution.changedTrackerField(latest, "nextAction")) 1 else 0,
                        executions = (latest.executions + execution).takeLast(SCHEDULER_HISTORY_LIMIT),
                    )
                    persistLocked(current.copy(schedules = current.schedules.map {
                        if (it.id == latest.id) updated else it
                    }))
                    inFlight -= due.id
                    wake.trySend(Unit)
                }
            } catch (error: Exception) {
                mutex.withLock { inFlight -= due.id }
                throw error
            }
            runs++
        }
    }

    override fun close() {
        loopJob?.cancel()
        job.cancel()
        wake.close()
    }

    private fun persistLocked(candidate: SchedulerState) {
        store.save(candidate)
        current = candidate
    }
}

fun SchedulerSummary.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("taskType", taskType.name.lowercase())
    put("status", status.name.lowercase())
    put("scheduleType", scheduleType.name.lowercase())
    put("aggregationPeriod", aggregationPeriod)
    put("totalRuns", totalRuns)
    put("successfulRuns", successfulRuns)
    put("failedRuns", failedRuns)
    lastRunAt?.let { put("lastRunAt", it.toString()) }
    nextRunAt?.let { put("nextRunAt", it.toString()) }
    lastResult?.let { put("lastResult", it) }
    lastError?.let { put("lastError", it) }
    put("snapshotCount", snapshotCount)
    latestTrackerStatus?.let { put("latestTrackerStatus", it) }
    latestTrackerNextAction?.let { put("latestTrackerNextAction", it) }
    put("statusChanges", statusChanges)
    put("nextActionChanges", nextActionChanges)
    put("summary", summary)
}

fun SchedulerSnapshot.toJson(): JsonObject = buildJsonObject {
    put("available", available)
    error?.let { put("error", it) }
    put("schedules", buildJsonArray { schedules.forEach { add(it.toJson()) } })
}

fun schedulerSnapshotFromJson(element: JsonElement): SchedulerSnapshot {
    val root = element.jsonObject
    return SchedulerSnapshot(
        available = root["available"]?.jsonPrimitive?.booleanOrNull ?: true,
        error = root["error"]?.jsonPrimitive?.contentOrNull,
        schedules = root["schedules"]?.jsonArray.orEmpty().map { item ->
            val value = item.jsonObject
            SchedulerSummary(
                id = value.requiredString("id"),
                title = value.requiredString("title"),
                taskType = ScheduledTaskType.valueOf(value.requiredString("taskType").uppercase()),
                status = ScheduleStatus.valueOf(value.requiredString("status").uppercase()),
                scheduleType = ScheduleType.valueOf(value.requiredString("scheduleType").uppercase()),
                aggregationPeriod = value.requiredString("aggregationPeriod"),
                totalRuns = value.requiredInt("totalRuns"),
                successfulRuns = value.requiredInt("successfulRuns"),
                failedRuns = value.requiredInt("failedRuns"),
                lastRunAt = value.optionalInstant("lastRunAt"),
                nextRunAt = value.optionalInstant("nextRunAt"),
                lastResult = value["lastResult"]?.jsonPrimitive?.contentOrNull,
                lastError = value["lastError"]?.jsonPrimitive?.contentOrNull,
                snapshotCount = value.requiredInt("snapshotCount"),
                latestTrackerStatus = value["latestTrackerStatus"]?.jsonPrimitive?.contentOrNull,
                latestTrackerNextAction = value["latestTrackerNextAction"]?.jsonPrimitive?.contentOrNull,
                statusChanges = value.requiredInt("statusChanges"),
                nextActionChanges = value.requiredInt("nextActionChanges"),
                summary = value.requiredString("summary"),
            )
        },
    )
}

private fun summarize(task: ScheduledTask): SchedulerSummary {
    val last = task.executions.lastOrNull()
    val summary = when (task.taskType) {
        ScheduledTaskType.REMINDER -> when {
            last?.success == true -> "Напоминание выполнено: ${task.reminderText}."
            last != null -> "Напоминание завершилось ошибкой: ${last.error}."
            else -> "Напоминание ожидает выполнения."
        }
        ScheduledTaskType.TRACKER_SNAPSHOT -> when {
            task.snapshotCount > 0 -> "Собрано снимков: ${task.snapshotCount}; статус: ${task.latestTrackerStatus}; изменений статуса: ${task.statusChanges}; изменений следующего действия: ${task.nextActionChanges}."
            task.failedRuns > 0 -> "Снимки Tracker пока не собраны; ошибок: ${task.failedRuns}."
            else -> "Сбор снимков Tracker ещё не выполнялся."
        }
    }
    return SchedulerSummary(
        id = task.id,
        title = task.title,
        taskType = task.taskType,
        status = task.status,
        scheduleType = task.scheduleType,
        aggregationPeriod = "${task.createdAt}/${task.lastRunAt ?: task.createdAt}",
        totalRuns = task.totalRuns,
        successfulRuns = task.successfulRuns,
        failedRuns = task.failedRuns,
        lastRunAt = task.lastRunAt,
        nextRunAt = task.nextRunAt,
        lastResult = last?.result?.toString(),
        lastError = last?.error,
        snapshotCount = task.snapshotCount,
        latestTrackerStatus = task.latestTrackerStatus,
        latestTrackerNextAction = task.latestTrackerNextAction,
        statusChanges = task.statusChanges,
        nextActionChanges = task.nextActionChanges,
        summary = summary,
    )
}

private fun validateCreateCommand(command: SchedulerCreateCommand, now: Instant): SchedulerCreateCommand {
    val normalized = command.copy(
        requestId = validatedId(command.requestId, "requestId"),
        title = validatedText(command.title, "title", 200),
        reminderText = command.reminderText?.let { validatedText(it, "reminderText", 4_000) },
        issueId = command.issueId?.let(::validatedIssueId),
    )
    when (normalized.scheduleType) {
        ScheduleType.ONCE -> {
            require(normalized.runAt != null) { "runAt обязателен для once." }
            require(normalized.everySeconds == null && normalized.startAt == null) {
                "everySeconds/startAt недопустимы для once."
            }
            require(!normalized.runAt.isBefore(now)) { "runAt не может быть в прошлом." }
        }
        ScheduleType.FIXED_INTERVAL -> {
            require(normalized.runAt == null) { "runAt недопустим для fixed_interval." }
            require(normalized.everySeconds in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) {
                "everySeconds должен быть от $MIN_INTERVAL_SECONDS до $MAX_INTERVAL_SECONDS."
            }
            require(normalized.startAt == null || !normalized.startAt.isBefore(now)) {
                "startAt не может быть в прошлом."
            }
        }
    }
    when (normalized.taskType) {
        ScheduledTaskType.REMINDER -> require(normalized.reminderText != null && normalized.issueId == null) {
            "Для reminder требуется только reminderText."
        }
        ScheduledTaskType.TRACKER_SNAPSHOT -> require(normalized.issueId != null && normalized.reminderText == null) {
            "Для tracker_snapshot требуется только issueId."
        }
    }
    listOf(normalized.requestId, normalized.title, normalized.reminderText, normalized.issueId)
        .filterNotNull().forEach(::rejectSecretLikeText)
    return normalized
}

private fun ScheduledTask.matches(command: SchedulerCreateCommand): Boolean =
    title == command.title && scheduleType == command.scheduleType && runAt == command.runAt &&
        startAt == command.startAt && everySeconds == command.everySeconds && taskType == command.taskType &&
        reminderText == command.reminderText && issueId == command.issueId

private fun validatedId(value: String, field: String): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= 200 && it.none(Char::isISOControl)) {
        "$field должен быть непустой строкой до 200 символов."
    }
}

private fun validatedText(value: String, field: String, max: Int): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= max && it.none { char -> char.isISOControl() && char != '\n' && char != '\t' }) {
        "$field должен быть непустой безопасной строкой до $max символов."
    }
}

private fun validatedIssueId(value: String): String = validatedText(value, "issueId", 64).also {
    require(it.matches(Regex("[A-Z][A-Z0-9_]*-[1-9][0-9]*"))) {
        "issueId имеет неверный формат Tracker."
    }
}

internal fun rejectSecretLikeText(value: String) {
    val secretLike = listOf(
        Regex("(?i)bearer\\s+[^\\s]+"),
        Regex("(?i)sk-[a-z0-9_-]{8,}"),
        Regex("(?i)(api[_-]?key|access[_-]?token|secret)\\s*[:=]"),
    ).any { it.containsMatchIn(value) }
    require(!secretLike) { "Значение похоже на секрет и не может быть сохранено в расписании." }
}

private fun safeExecutionError(error: Throwable): String = when (error) {
    is IllegalArgumentException -> error.message.orEmpty().take(500)
    else -> "Не удалось выполнить фоновую задачу."
}.ifBlank { "Не удалось выполнить фоновую задачу." }

private fun TrackerIssue.toJson() = buildJsonObject {
    put("taskType", "tracker_snapshot")
    put("id", id)
    put("title", title)
    put("status", status)
    put("assignee", assignee)
    put("description", description)
    put("nextAction", nextAction)
}

private fun ScheduledExecution.isTrackerSnapshot(): Boolean = success &&
    result?.get("taskType")?.jsonPrimitive?.contentOrNull == "tracker_snapshot"

private fun ScheduledExecution.trackerField(field: String): String? =
    if (isTrackerSnapshot()) result?.get(field)?.jsonPrimitive?.contentOrNull else null

private fun ScheduledExecution.changedTrackerField(task: ScheduledTask, field: String): Boolean {
    if (!isTrackerSnapshot() || task.snapshotCount == 0) return false
    val previous = if (field == "status") task.latestTrackerStatus else task.latestTrackerNextAction
    return previous != trackerField(field)
}

internal const val DEMO_ISSUE_ID = "DEMO-101"

internal fun demoTrackerIssue() = TrackerIssue(
    id = DEMO_ISSUE_ID,
    title = "Подготовить первый MCP-инструмент",
    status = "In Progress",
    assignee = "Ирина Волкова",
    description = "Подключить локальный Tracker к Простому агенту через MCP stdio.",
    nextAction = "Завершить сквозные тесты и отправить изменение на проверку.",
)

private fun validateStoredSchedulerState(state: SchedulerState) {
    require(state.schedules.map(ScheduledTask::id).distinct().size == state.schedules.size) { "Duplicate schedule id" }
    require(state.schedules.map(ScheduledTask::requestId).distinct().size == state.schedules.size) { "Duplicate scheduler requestId" }
    state.schedules.forEach { task ->
        validatedId(task.id, "id")
        validatedId(task.requestId, "requestId")
        require(validatedText(task.title, "title", 200) == task.title) { "Invalid stored title" }
        listOfNotNull(task.requestId, task.title, task.reminderText, task.issueId).forEach(::rejectSecretLikeText)
        require(task.executions.size <= SCHEDULER_HISTORY_LIMIT) { "Scheduler history limit exceeded" }
        require(task.totalRuns >= task.executions.size && task.successfulRuns + task.failedRuns == task.totalRuns) {
            "Invalid scheduler counters"
        }
        require(task.snapshotCount in 0..task.successfulRuns && task.statusChanges >= 0 && task.nextActionChanges >= 0) {
            "Invalid scheduler aggregate counters"
        }
        require(task.createdAt.toEpochMilli() >= 0) { "Invalid scheduler timestamp" }
        task.executions.forEach { execution ->
            validatedId(execution.id, "executionId")
            require(!execution.completedAt.isBefore(execution.startedAt)) { "Invalid execution timestamps" }
            require(
                if (execution.success) execution.result != null && execution.error == null
                else execution.result == null && execution.error != null
            ) { "Invalid execution result" }
        }
        when (task.scheduleType) {
            ScheduleType.ONCE -> require(task.runAt != null && task.startAt == null && task.everySeconds == null) { "Invalid once schedule" }
            ScheduleType.FIXED_INTERVAL -> require(task.runAt == null && task.everySeconds in MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS) { "Invalid interval schedule" }
        }
        when (task.taskType) {
            ScheduledTaskType.REMINDER -> {
                require(task.reminderText != null && task.issueId == null) { "Invalid reminder task" }
                require(validatedText(task.reminderText, "reminderText", 4_000) == task.reminderText) {
                    "Invalid stored reminder text"
                }
            }
            ScheduledTaskType.TRACKER_SNAPSHOT -> {
                require(task.issueId != null && task.reminderText == null) { "Invalid tracker task" }
                require(validatedIssueId(task.issueId) == task.issueId) { "Invalid stored Tracker issue id" }
            }
        }
        task.executions.forEach { execution ->
            execution.error?.let(::rejectSecretLikeText)
            execution.result?.toString()?.let(::rejectSecretLikeText)
        }
    }
}

private const val SCHEDULER_FORMAT_VERSION = 1

@Serializable
private data class StoredSchedulerDocument(
    val formatVersion: Int,
    val schedules: List<StoredScheduledTask>,
) {
    fun toDomain() = SchedulerState(schedules.map(StoredScheduledTask::toDomain))
    companion object {
        fun fromDomain(state: SchedulerState) = StoredSchedulerDocument(
            SCHEDULER_FORMAT_VERSION,
            state.schedules.map(StoredScheduledTask::fromDomain),
        )
    }
}

@Serializable
private data class StoredScheduledTask(
    val id: String,
    val requestId: String,
    val title: String,
    val scheduleType: String,
    val runAt: String?,
    val startAt: String?,
    val everySeconds: Long?,
    val taskType: String,
    val reminderText: String?,
    val issueId: String?,
    val status: String,
    val createdAt: String,
    val lastRunAt: String?,
    val nextRunAt: String?,
    val totalRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val snapshotCount: Int,
    val latestTrackerStatus: String?,
    val latestTrackerNextAction: String?,
    val statusChanges: Int,
    val nextActionChanges: Int,
    val executions: List<StoredScheduledExecution>,
) {
    fun toDomain() = ScheduledTask(
        id, requestId, title, ScheduleType.valueOf(scheduleType), runAt?.let(Instant::parse), startAt?.let(Instant::parse), everySeconds,
        ScheduledTaskType.valueOf(taskType), reminderText, issueId, ScheduleStatus.valueOf(status),
        Instant.parse(createdAt), lastRunAt?.let(Instant::parse), nextRunAt?.let(Instant::parse),
        totalRuns, successfulRuns, failedRuns, snapshotCount, latestTrackerStatus, latestTrackerNextAction,
        statusChanges, nextActionChanges,
        executions.map(StoredScheduledExecution::toDomain),
    )
    companion object {
        fun fromDomain(value: ScheduledTask) = StoredScheduledTask(
            value.id, value.requestId, value.title, value.scheduleType.name, value.runAt?.toString(),
            value.startAt?.toString(), value.everySeconds, value.taskType.name, value.reminderText, value.issueId, value.status.name,
            value.createdAt.toString(), value.lastRunAt?.toString(), value.nextRunAt?.toString(),
            value.totalRuns, value.successfulRuns, value.failedRuns, value.snapshotCount,
            value.latestTrackerStatus, value.latestTrackerNextAction, value.statusChanges, value.nextActionChanges,
            value.executions.map(StoredScheduledExecution::fromDomain),
        )
    }
}

@Serializable
private data class StoredScheduledExecution(
    val id: String,
    val startedAt: String,
    val completedAt: String,
    val success: Boolean,
    val result: JsonObject?,
    val error: String?,
) {
    fun toDomain() = ScheduledExecution(
        id, Instant.parse(startedAt), Instant.parse(completedAt), success, result, error,
    )
    companion object {
        fun fromDomain(value: ScheduledExecution) = StoredScheduledExecution(
            value.id, value.startedAt.toString(), value.completedAt.toString(), value.success, value.result, value.error,
        )
    }
}

internal fun parseInstant(value: String, field: String): Instant = try {
    Instant.parse(value)
} catch (_: DateTimeParseException) {
    throw IllegalArgumentException("$field должен быть ISO-8601 timestamp с UTC/offset.")
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Missing scheduler field $name")

private fun JsonObject.requiredInt(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Missing scheduler field $name")

private fun JsonObject.optionalInstant(name: String): Instant? =
    this[name]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
