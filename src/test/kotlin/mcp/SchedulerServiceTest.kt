package org.example.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SchedulerServiceTest {
    private val base = Instant.parse("2026-09-23T12:00:00Z")

    @Test
    fun `creates once and periodic schedules idempotently and cancels without deleting summary`() = runBlocking {
        val clock = MutableClock(base)
        val service = service(clock = clock)
        try {
            val once = service.create(reminder("once-1", base.plusSeconds(60)))
            val repeated = service.create(reminder("once-1", base.plusSeconds(60)))
            assertEquals(once.id, repeated.id)
            assertFailsWith<IllegalArgumentException> {
                service.create(reminder("once-1", base.plusSeconds(60)).copy(title = "Другое"))
            }

            val periodic = service.create(SchedulerCreateCommand(
                requestId = "periodic-1",
                title = "Снимки Tracker",
                scheduleType = ScheduleType.FIXED_INTERVAL,
                everySeconds = 1_800,
                taskType = ScheduledTaskType.TRACKER_SNAPSHOT,
                issueId = "DEMO-101",
            ))
            assertEquals(base.plusSeconds(1_800), periodic.nextRunAt)
            assertEquals(ScheduleStatus.CANCELLED, service.cancel(periodic.id).status)
            assertEquals(ScheduleStatus.CANCELLED, service.cancel(periodic.id).status)
            assertEquals(2, service.summaries().schedules.size)
        } finally { service.close() }
    }

    @Test
    fun `strict validation rejects mixed fields invalid intervals timestamps and secret-like text`() = runBlocking {
        val service = service()
        try {
            assertEquals(base, parseInstant("2026-09-23T15:00:00+03:00", "runAt"))
            assertFailsWith<IllegalArgumentException> { parseInstant("tomorrow", "runAt") }
            assertFailsWith<IllegalArgumentException> {
                service.create(reminder("bad-run", base.minusSeconds(1)))
            }
            assertFailsWith<IllegalArgumentException> {
                service.create(reminder("bad-mixed", base.plusSeconds(1)).copy(everySeconds = 10))
            }
            assertFailsWith<IllegalArgumentException> {
                service.create(SchedulerCreateCommand(
                    "bad-interval", "Bad", ScheduleType.FIXED_INTERVAL, everySeconds = 0,
                    taskType = ScheduledTaskType.REMINDER, reminderText = "text",
                ))
            }
            assertFailsWith<IllegalArgumentException> {
                service.create(reminder("bad-secret", base.plusSeconds(1)).copy(reminderText = "Bearer abcdefgh"))
            }
            assertFailsWith<IllegalArgumentException> {
                service.create(reminder("bad-task", base.plusSeconds(1)).copy(issueId = "DEMO-101"))
            }
        } finally { service.close() }
    }

    @Test
    fun `active schedule limit is enforced`() = runBlocking {
        val service = service()
        try {
            repeat(MAX_ACTIVE_SCHEDULES) { index ->
                service.create(reminder("limit-$index", base.plusSeconds((index + 1).toLong())))
            }
            val error = assertFailsWith<IllegalArgumentException> {
                service.create(reminder("limit-overflow", base.plusSeconds(1_000)))
            }
            assertContains(error.message.orEmpty(), MAX_ACTIVE_SCHEDULES.toString())
        } finally { service.close() }
    }

    @Test
    fun `due once runs exactly once and periodic reschedules after success and error without catch-up storm`() = runBlocking {
        val clock = MutableClock(base)
        val calls = AtomicInteger()
        val executor = ScheduledTaskExecutor {
            if (calls.getAndIncrement() == 0) error("first failure")
            buildJsonObject { put("taskType", "reminder"); put("text", "ok") }
        }
        val service = service(clock, executor)
        try {
            val once = service.create(reminder("once", base))
            assertEquals(1, service.runDue(base))
            assertEquals(ScheduleStatus.FAILED, service.summaries(once.id).schedules.single().status)
            assertEquals(0, service.runDue(base))

            val periodic = service.create(SchedulerCreateCommand(
                "periodic", "Periodic", ScheduleType.FIXED_INTERVAL,
                everySeconds = 30, startAt = base, taskType = ScheduledTaskType.REMINDER,
                reminderText = "tick",
            ))
            val afterDowntime = base.plusSeconds(3_600)
            clock.current = afterDowntime
            assertEquals(1, service.runDue(afterDowntime))
            val afterSuccess = service.summaries(periodic.id).schedules.single()
            assertEquals(1, afterSuccess.successfulRuns)
            assertEquals(afterDowntime.plusSeconds(30), afterSuccess.nextRunAt)
            assertEquals(0, service.runDue(afterDowntime))
        } finally { service.close() }
    }

    @Test
    fun `creating a due schedule wakes the background loop immediately`() = runBlocking {
        val service = service()
        try {
            service.start()
            val task = service.create(reminder("wake", base))
            val summary = withTimeout(1_000) {
                while (true) {
                    val current = service.summaries(task.id).schedules.single()
                    if (current.status == ScheduleStatus.COMPLETED) return@withTimeout current
                    yield()
                }
                error("unreachable")
            }
            assertEquals(1, summary.totalRuns)
        } finally { service.close() }
    }

    @Test
    fun `periodic failure is retained and a later run remains scheduled`() = runBlocking {
        val clock = MutableClock(base)
        var fail = true
        val service = service(clock, ScheduledTaskExecutor {
            if (fail) error("boom")
            buildJsonObject { put("taskType", "reminder"); put("text", "ok") }
        })
        try {
            val task = service.create(SchedulerCreateCommand(
                "retry", "Retry", ScheduleType.FIXED_INTERVAL, everySeconds = 10, startAt = base,
                taskType = ScheduledTaskType.REMINDER, reminderText = "tick",
            ))
            service.runDue(base)
            var summary = service.summaries(task.id).schedules.single()
            assertEquals(ScheduleStatus.ACTIVE, summary.status)
            assertEquals(1, summary.failedRuns)
            assertEquals(base.plusSeconds(10), summary.nextRunAt)
            fail = false
            clock.current = base.plusSeconds(10)
            service.runDue(clock.instant())
            summary = service.summaries(task.id).schedules.single()
            assertEquals(2, summary.totalRuns)
            assertEquals(1, summary.successfulRuns)
            assertEquals(1, summary.failedRuns)
            assertEquals(base.plusSeconds(20), summary.nextRunAt)
        } finally { service.close() }
    }

    @Test
    fun `concurrent runDue never executes one schedule in parallel`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = service(executor = ScheduledTaskExecutor {
            entered.complete(Unit)
            release.await()
            buildJsonObject { put("taskType", "reminder"); put("text", "done") }
        })
        try {
            service.create(reminder("parallel", base))
            val first = async { service.runDue(base) }
            entered.await()
            assertEquals(0, service.runDue(base))
            release.complete(Unit)
            assertEquals(1, first.await())
        } finally { service.close() }
    }

    @Test
    fun `cancelling an in-flight execution does not persist a partial result`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val service = service(executor = ScheduledTaskExecutor {
            entered.complete(Unit)
            awaitCancellation()
        })
        try {
            val task = service.create(reminder("cancel-run", base))
            val run = launch { service.runDue(base) }
            entered.await()
            run.cancelAndJoin()
            val summary = service.summaries(task.id).schedules.single()
            assertEquals(ScheduleStatus.ACTIVE, summary.status)
            assertEquals(0, summary.totalRuns)
        } finally { service.close() }
    }

    @Test
    fun `restart restores an overdue once schedule and executes it only once`() = runBlocking {
        val directory = Files.createTempDirectory("scheduler-restart")
        val file = directory.resolve("state.json")
        try {
            val first = service(MutableClock(base), store = JsonSchedulerStore(file))
            first.create(reminder("restore", base.plusSeconds(60)))
            first.close()

            val restoredClock = MutableClock(base.plusSeconds(3_600))
            val restored = service(restoredClock, store = JsonSchedulerStore(file))
            try {
                assertEquals(1, restored.runDue(restoredClock.instant()))
                assertEquals(0, restored.runDue(restoredClock.instant()))
                val summary = restored.summaries().schedules.single()
                assertEquals(ScheduleStatus.COMPLETED, summary.status)
                assertEquals(1, summary.totalRuns)
            } finally { restored.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `tracker aggregation detects status and next-action changes`() = runBlocking {
        val clock = MutableClock(base)
        val issues = ArrayDeque(listOf(
            TrackerIssue("DEMO-101", "Demo", "Open", "Ирина", "d", "Plan"),
            TrackerIssue("DEMO-101", "Demo", "In Progress", "Ирина", "d", "Validate"),
        ))
        val service = SchedulerService(
            InMemorySchedulerStore(),
            LocalScheduledTaskExecutor(TrackerSource { issues.removeFirst() }),
            clock,
        )
        try {
            val task = service.create(SchedulerCreateCommand(
                "tracker", "Tracker", ScheduleType.FIXED_INTERVAL, everySeconds = 60, startAt = base,
                taskType = ScheduledTaskType.TRACKER_SNAPSHOT, issueId = "DEMO-101",
            ))
            service.runDue(base)
            clock.current = base.plusSeconds(60)
            service.runDue(clock.instant())
            val summary = service.summaries(task.id).schedules.single()
            assertEquals(2, summary.snapshotCount)
            assertEquals("In Progress", summary.latestTrackerStatus)
            assertEquals("Validate", summary.latestTrackerNextAction)
            assertEquals(1, summary.statusChanges)
            assertEquals(1, summary.nextActionChanges)
            assertContains(summary.summary, "Собрано снимков: 2")
        } finally { service.close() }
    }

    @Test
    fun `versioned JSON round trips atomically rejects unknown version and caps retained history`() = runBlocking {
        val directory = Files.createTempDirectory("scheduler-store")
        val file = directory.resolve("state.json")
        val store = JsonSchedulerStore(file)
        val clock = MutableClock(base)
        val service = service(clock, store = store)
        try {
            service.create(SchedulerCreateCommand(
                "history", "History", ScheduleType.FIXED_INTERVAL, everySeconds = 1, startAt = base,
                taskType = ScheduledTaskType.REMINDER, reminderText = "tick",
            ))
            repeat(SCHEDULER_HISTORY_LIMIT + 5) {
                service.runDue(clock.instant())
                clock.current = clock.current.plusSeconds(1)
            }
            val stored = store.load().schedules.single()
            assertEquals(SCHEDULER_HISTORY_LIMIT + 5, stored.totalRuns)
            assertEquals(SCHEDULER_HISTORY_LIMIT, stored.executions.size)
            assertContains(Files.readString(file), "\"formatVersion\": 1")
            assertTrue(Files.list(directory).use { paths -> paths.noneMatch { it.fileName.toString().endsWith(".tmp") } })

            Files.writeString(file, """{"formatVersion":999,"schedules":[]}""")
            assertFailsWith<IllegalArgumentException> { store.load() }
            val recovered = service(clock, store = store)
            try {
                assertNotNull(recovered.loadWarning)
                assertTrue(recovered.summaries().schedules.isEmpty())
            } finally { recovered.close() }
        } finally {
            service.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun reminder(requestId: String, runAt: Instant) = SchedulerCreateCommand(
        requestId = requestId,
        title = "Напоминание",
        scheduleType = ScheduleType.ONCE,
        runAt = runAt,
        taskType = ScheduledTaskType.REMINDER,
        reminderText = "Проверить DEMO-101",
    )

    private fun service(
        clock: MutableClock = MutableClock(base),
        executor: ScheduledTaskExecutor = ScheduledTaskExecutor {
            buildJsonObject { put("taskType", "reminder"); put("text", "ok") }
        },
        store: SchedulerStore = InMemorySchedulerStore(),
    ): SchedulerService {
        var id = 0
        return SchedulerService(store, executor, clock, idFactory = { "id-${++id}" })
    }
}

private class MutableClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
}
