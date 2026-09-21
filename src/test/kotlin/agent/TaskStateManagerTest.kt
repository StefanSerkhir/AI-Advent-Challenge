package org.example.agent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.llm.*
import org.example.tokens.ContextOverflowPolicy
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class TaskStateManagerTest {
    private val draft = NewTaskDraft(
        goal = "Подготовить релиз",
        currentStep = "Составить план",
        expectedAction = "Выбрать порядок изменений",
    )

    @Test
    fun `valid lifecycle advances one phase at a time and done is terminal`() {
        val ids = ArrayDeque(listOf("task-one", "task-two", "task-three"))
        val manager = TaskStateManager(
            InMemoryTaskStateStore(),
            clock = { 100 },
            idFactory = { ids.removeFirst() },
        )
        val planning = manager.start(draft)
        assertEquals(TaskPhase.PLANNING, planning.phase)
        assertEquals(1, planning.version)

        val execution = manager.approvePlan()
        val validation = manager.completeImplementation()
        val done = manager.recordValidation(TaskValidationDraft(true, "Все проверки прошли"))

        assertEquals(TaskPhase.EXECUTION, execution.phase)
        assertEquals(TaskPhase.VALIDATION, validation.phase)
        assertEquals(TaskPhase.DONE, done.phase)
        assertNotNull(execution.planApprovedAtEpochMillis)
        assertNotNull(validation.implementationCompletedAtEpochMillis)
        assertEquals(TaskValidationStatus.PASSED, done.validationStatus)
        assertEquals("Все проверки прошли", done.validationDetails)
        assertEquals(listOf(2L, 3L, 4L), listOf(execution.version, validation.version, done.version))
        assertFalse(done.paused)
        val snapshot = manager.state()
        assertFailsWith<InvalidTaskTransitionException> { manager.approvePlan() }
        assertFailsWith<InvalidTaskTransitionException> { manager.pause() }
        assertFailsWith<InvalidTaskTransitionException> { manager.resume() }
        assertFailsWith<InvalidTaskTransitionException> {
            manager.updateProgress(TaskProgressDraft("Назад", "Повторить"))
        }
        assertEquals(snapshot, manager.state())

        val replacement = manager.start(draft.copy(goal = "Следующий релиз"))
        assertEquals(TaskPhase.PLANNING, replacement.phase)
        assertNotEquals(done.id, replacement.id)
        manager.reset()
        val afterReset = manager.start(draft.copy(goal = "После reset"))
        assertEquals(TaskPhase.PLANNING, afterReset.phase)
        assertNotEquals(replacement.id, afterReset.id)
    }

    @Test
    fun `backward skipped and repeated transitions are rejected without writes`() {
        val store = CountingTaskStore()
        val manager = manager(store)
        manager.start(draft)
        val planning = manager.state()

        assertFailsWith<InvalidTaskTransitionException> { manager.transitionTo(TaskPhase.EXECUTION) }
        assertFailsWith<InvalidTaskTransitionException> { manager.transitionTo(TaskPhase.VALIDATION) }
        assertFailsWith<InvalidTaskTransitionException> { manager.transitionTo(TaskPhase.DONE) }
        assertFailsWith<InvalidTaskTransitionException> { manager.transitionTo(TaskPhase.PLANNING) }
        assertFailsWith<InvalidTaskTransitionException> { manager.completeImplementation() }
        assertFailsWith<InvalidTaskTransitionException> {
            manager.recordValidation(TaskValidationDraft(true, "Проверено"))
        }
        assertFailsWith<InvalidTaskTransitionException> { manager.resume() }
        assertEquals(planning, manager.state())
        assertEquals(1, store.saves)

        manager.pause()
        val paused = manager.state()
        assertFailsWith<InvalidTaskTransitionException> { manager.pause() }
        assertFailsWith<InvalidTaskTransitionException> { manager.approvePlan() }
        assertFailsWith<InvalidTaskTransitionException> {
            manager.updateProgress(TaskProgressDraft("Нельзя", "На паузе"))
        }
        assertEquals(paused, manager.state())
        assertEquals(2, store.saves)

        manager.resume()
        manager.approvePlan()
        val execution = manager.state()
        assertFailsWith<InvalidTaskTransitionException> { manager.transitionTo(TaskPhase.PLANNING) }
        assertFailsWith<InvalidTaskTransitionException> { manager.transitionTo(TaskPhase.DONE) }
        assertFailsWith<InvalidTaskTransitionException> { manager.approvePlan() }
        assertEquals(execution, manager.state())
        assertEquals(4, store.saves)
    }

    @Test
    fun `pause and resume preserve task data in every unfinished phase`() {
        val manager = manager()
        manager.start(draft)
        for (phase in listOf(TaskPhase.PLANNING, TaskPhase.EXECUTION, TaskPhase.VALIDATION)) {
            assertEquals(phase, manager.state()?.phase)
            val before = requireNotNull(manager.state())
            val paused = manager.pause()
            val resumed = manager.resume()
            assertTrue(paused.paused)
            assertFalse(resumed.paused)
            assertEquals(before.copy(version = resumed.version, updatedAtEpochMillis = resumed.updatedAtEpochMillis), resumed)
            if (phase == TaskPhase.PLANNING) manager.approvePlan()
            if (phase == TaskPhase.EXECUTION) manager.completeImplementation()
        }
    }

    @Test
    fun `json round trip reset and safe recovery use a separate versioned document`() {
        val directory = Files.createTempDirectory("task-state")
        try {
            val file = directory.resolve("task.json")
            val first = manager(JsonTaskStateStore(file))
            first.start(draft)
            first.approvePlan()
            first.updateProgress(TaskProgressDraft("Реализовать FSM", "Запустить тесты"))
            first.pause()

            val restored = TaskStateManager(JsonTaskStateStore(file))
            assertEquals(first.state(), restored.state())
            assertNull(restored.loadWarning)
            assertContains(Files.readString(file), "\"formatVersion\": 2")

            restored.reset()
            assertNull(TaskStateManager(JsonTaskStateStore(file)).state())
            assertContains(Files.readString(file), "\"task\": null")

            Files.writeString(file, """
                {
                  "formatVersion": 1,
                  "task": {
                    "id": "legacy-task",
                    "version": 3,
                    "goal": "Восстановить старую задачу",
                    "phase": "VALIDATION",
                    "currentStep": "Проверить результат",
                    "expectedAction": "Зафиксировать проверку",
                    "paused": true,
                    "createdAtEpochMillis": 10,
                    "updatedAtEpochMillis": 20
                  }
                }
            """.trimIndent())
            val migrated = requireNotNull(TaskStateManager(JsonTaskStateStore(file)).state())
            assertEquals(TaskPhase.VALIDATION, migrated.phase)
            assertTrue(migrated.paused)
            assertNotNull(migrated.planApprovedAtEpochMillis)
            assertNotNull(migrated.implementationCompletedAtEpochMillis)
            assertEquals(TaskValidationStatus.NOT_RUN, migrated.validationStatus)

            Files.writeString(file, "{broken")
            val corrupt = TaskStateManager(JsonTaskStateStore(file))
            assertNull(corrupt.state())
            assertContains(requireNotNull(corrupt.loadWarning), "пустое состояние")

            Files.writeString(file, """{"formatVersion":999,"task":null}""")
            val unsupported = TaskStateManager(JsonTaskStateStore(file))
            assertNull(unsupported.state())
            assertContains(requireNotNull(unsupported.loadWarning), "неподдерживаемую версию")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed validation stays in validation and successful evidence is required for done`() {
        val store = CountingTaskStore()
        val manager = manager(store)
        manager.start(draft)
        manager.approvePlan()
        manager.completeImplementation()
        val validation = manager.state()

        assertFailsWith<InvalidTaskTransitionException> { manager.transitionTo(TaskPhase.DONE) }
        assertEquals(validation, manager.state())
        assertEquals(3, store.saves)
        val failed = manager.recordValidation(TaskValidationDraft(
            successful = false,
            details = "Интеграционный тест упал",
            expectedAction = "Исправить обработку ошибки и повторить тест",
        ))
        assertEquals(TaskPhase.VALIDATION, failed.phase)
        assertEquals(TaskValidationStatus.FAILED, failed.validationStatus)
        assertEquals("Интеграционный тест упал", failed.validationDetails)
        assertEquals("Исправить обработку ошибки и повторить тест", failed.expectedAction)

        val done = manager.recordValidation(TaskValidationDraft(true, "Повторный прогон успешен"))
        assertEquals(TaskPhase.DONE, done.phase)
        assertEquals(TaskValidationStatus.PASSED, done.validationStatus)
        assertEquals("Повторный прогон успешен", done.validationDetails)
    }

    @Test
    fun `persistence failure and secret validation leave published state unchanged`() {
        val initial = manager().apply { start(draft) }.state()
        val failing = object : TaskStateStore {
            override fun load() = initial
            override fun save(state: AgentTaskState?) { throw IOException("disk full") }
        }
        val manager = TaskStateManager(failing, clock = { 200 })
        assertFailsWith<TaskStatePersistenceException> { manager.approvePlan() }
        assertEquals(initial, manager.state())

        val protected = TaskStateManager(
            InMemoryTaskStateStore(),
            sensitiveText = { "configured-secret" in it },
        )
        assertFailsWith<IllegalArgumentException> {
            protected.start(draft.copy(goal = "Использовать configured-secret"))
        }
        assertNull(protected.state())
        protected.start(draft)
        val safe = protected.state()
        assertFailsWith<IllegalArgumentException> {
            protected.updateProgress(TaskProgressDraft("configured-secret", "Дальше"))
        }
        assertEquals(safe, protected.state())
    }

    @Test
    fun `active task is embedded in system context and diagnostics without duplicating prompt`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        memory.add(MemoryLayer.LONG_TERM, "Стабильный факт")
        memory.add(MemoryLayer.WORKING, "Рабочее ограничение")
        memory.commitShortTermPair("Ранее", "Ответ ранее", 10)
        val tasks = manager().apply { start(draft) }
        val client = RecordingTaskClient()
        val response = AssistantAgent(
            memory,
            taskStateProvider = tasks::state,
            clientProvider = { client },
        ).respond("Продолжай", "unknown", 100, ContextOverflowPolicy.REJECT, 10)

        val messages = client.calls.single()
        assertEquals(LlmRole.SYSTEM, messages.first().role)
        assertContains(messages.first().content, "TASK STATE DATA")
        assertContains(messages.first().content, "\"goal\":\"Подготовить релиз\"")
        assertContains(messages.first().content, "\"phase\":\"PLANNING\"")
        assertContains(messages[1].content, "LONG_TERM")
        assertContains(messages[2].content, "WORKING")
        assertContains(messages[3].content, "SHORT_TERM")
        assertEquals("Продолжай", messages.last().content)
        assertEquals(1, messages.count { it.content == "Продолжай" })
        assertTrue(response.taskStateDiagnostics.applied)
        assertEquals(tasks.state()?.id, response.taskStateDiagnostics.taskId)
        assertEquals(TaskPhase.PLANNING, response.taskStateDiagnostics.phase)

        tasks.approvePlan()
        tasks.completeImplementation()
        tasks.recordValidation(TaskValidationDraft(true, "Проверки успешны"))
        memory.newDialogue()
        val afterDoneClient = RecordingTaskClient()
        val afterDone = AssistantAgent(
            memory,
            taskStateProvider = tasks::state,
            clientProvider = { afterDoneClient },
        ).respond("Новый вопрос", "unknown", 100, ContextOverflowPolicy.REJECT, 10)
        assertFalse(afterDone.taskStateDiagnostics.applied)
        assertFalse(afterDoneClient.calls.single().first().content.contains("TASK STATE DATA"))
    }

    @Test
    fun `premature completion claim is blocked before publication and memory commit`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        val tasks = manager().apply { start(draft); approvePlan() }
        val unsafe = "Задача официально готова, считаем дело закрытым."
        val client = RecordingTaskClient(unsafe)
        val published = mutableListOf<String>()
        val taskBefore = tasks.state()
        val agent = AssistantAgent(
            memory,
            taskStateProvider = tasks::state,
            clientProvider = { client },
        )

        val response = agent.respond(
            "Заверши задачу", "unknown", 100, ContextOverflowPolicy.REJECT, 10, published::add,
        )

        assertTrue(response.taskStateDiagnostics.responseBlocked)
        assertContains(response.completion.content, "Ответ модели заблокирован")
        assertContains(response.completion.content, "Текущая фаза: EXECUTION")
        assertContains(response.completion.content, "Передать на проверку")
        assertFalse(response.completion.content.contains(unsafe))
        assertEquals(listOf(response.completion.content), published)
        assertTrue(memory.state().shortTerm.isEmpty())
        assertEquals(taskBefore, tasks.state())
        assertEquals(1, response.tokenMetrics.turnNumber)
        assertEquals(1, agent.tokenMetricsSnapshot().size)
    }

    @Test
    fun `compliant lifecycle refusal is published and committed`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        val tasks = manager().apply { start(draft); approvePlan() }
        val safe = "Текущая фаза EXECUTION. Задача не завершена; сначала передайте результат на проверку."
        val published = mutableListOf<String>()

        val response = AssistantAgent(
            memory,
            taskStateProvider = tasks::state,
            clientProvider = { RecordingTaskClient(safe) },
        ).respond("Можно завершать?", "unknown", 100, ContextOverflowPolicy.REJECT, 10, published::add)

        assertFalse(response.taskStateDiagnostics.responseBlocked)
        assertEquals(safe, response.completion.content)
        assertEquals(listOf(safe), published)
        assertEquals(listOf("Можно завершать?", safe), memory.state().shortTerm.map { it.text })
    }

    @Test
    fun `paused task is rejected before llm metrics and short term mutation`() = runBlocking {
        val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
        val tasks = manager().apply { start(draft); pause() }
        val client = RecordingTaskClient()
        val agent = AssistantAgent(memory, taskStateProvider = tasks::state, clientProvider = { client })
        val taskBefore = tasks.state()

        assertFailsWith<InvalidTaskTransitionException> {
            agent.respond("Продолжай", "unknown", 100, ContextOverflowPolicy.REJECT, 10)
        }
        assertTrue(client.calls.isEmpty())
        assertTrue(memory.state().shortTerm.isEmpty())
        assertTrue(agent.tokenMetricsSnapshot().isEmpty())
        assertEquals(taskBefore, tasks.state())
    }

    @Test
    fun `provider error cancellation and incomplete stream never mutate task state`() = runBlocking {
        suspend fun verify(client: LlmClient) {
            val memory = AssistantMemoryManager(InMemoryAssistantMemoryStore())
            val tasks = manager().apply { start(draft); approvePlan() }
            val before = tasks.state()
            val agent = AssistantAgent(memory, taskStateProvider = tasks::state, clientProvider = { client })
            runCatching {
                withTimeout(100) {
                    agent.respond("Выполняй", "unknown", 100, ContextOverflowPolicy.REJECT, 10)
                }
            }
            assertEquals(before, tasks.state())
            assertTrue(memory.state().shortTerm.isEmpty())
            assertTrue(agent.tokenMetricsSnapshot().isEmpty())
        }
        verify(object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = error("unused")
            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("черновик")); throw LlmApiException("provider error")
            }
        })
        verify(object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = error("unused")
            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("черновик")); awaitCancellation()
            }
        })
        verify(object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = error("unused")
            override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
                emit(TextDelta("поток завершился без CompletionFinished"))
            }
        })
    }

    private fun manager(store: TaskStateStore = InMemoryTaskStateStore()) = TaskStateManager(
        store,
        clock = { 100 },
        idFactory = { "task-stable-id" },
    )
}

private class CountingTaskStore : TaskStateStore {
    var state: AgentTaskState? = null
    var saves = 0
    override fun load(): AgentTaskState? = state
    override fun save(state: AgentTaskState?) {
        saves++
        this.state = state
    }
}

private class RecordingTaskClient(private val answer: String = "Готово") : LlmClient {
    val calls = mutableListOf<List<LlmMessage>>()
    override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
        calls += messages
        return CompletionResult(answer, "stop", TokenUsage(10, 3, 13), "test-model")
    }
}
