package org.example.web

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.example.agent.*
import org.example.app.AppSettings
import org.example.app.DEVELOPER_HELP_SYSTEM_PROMPT
import org.example.app.SUPPORT_ASSISTANT_SYSTEM_PROMPT
import org.example.app.WorkbenchController
import org.example.config.LocalConfigStore
import org.example.indexing.*
import org.example.llm.*
import org.example.mcp.*
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

/** Deliberately in test sources: no fixture switch or fake client in the production artifact. */
fun main() {
    val port = System.getenv("WEB_PORT")?.toInt() ?: 18080
    val directory = Files.createTempDirectory("workbench-browser-test")
    check(ProcessBuilder("git", "init", "-b", "fixture-main")
        .directory(directory.toFile())
        .redirectErrorStream(true)
        .start()
        .waitFor() == 0) { "Failed to initialize fixture Git repository" }
    createProjectFileAssistantFixture(directory)
    val ragIndex = runBlocking { createRagFixtureIndex(directory) }
    val store = LocalConfigStore(directory.resolve(".env"), emptyMap())
    val controller = WorkbenchController(AppSettings(LlmKind.OPENAI),
        mapOf(LlmKind.OPENAI to "fixture-openai-key", LlmKind.DEEPSEEK to "fixture-deepseek-key"),
        historyStore = JsonConversationHistoryStore(directory.resolve(".llm-history.json")),
        contextStateStore = JsonContextStateStore(directory.resolve(".llm-context-state.json")),
        assistantMemoryStore = JsonAssistantMemoryStore(directory.resolve(".llm-assistant-memory.json")),
        assistantInvariantStore = JsonAssistantInvariantStore(directory.resolve(".llm-assistant-invariants.json")),
        taskStateStore = JsonTaskStateStore(directory.resolve(".llm-task-state.json")),
        mcpGateway = LocalMcpGateway(
            directory.resolve(".llm-scheduler-state.json"),
            directory.resolve(".llm-mcp-output"),
            repositoryRoot = directory,
        ),
        documentRetrieverProvider = {
            DocumentRetriever(JsonDocumentIndexStore(ragIndex)) { descriptor ->
                check(descriptor.provider == "fake" && descriptor.model == "deterministic-hash-v1")
                object : EmbeddingClient {
                    private val delegate = DeterministicFakeEmbeddingClient(descriptor.dimensions, descriptor.model)
                    override val provider = delegate.provider
                    override val model = delegate.model
                    override suspend fun embed(texts: List<String>): List<EmbeddingVector> {
                        if (texts.any { "[[rag-retrieval-error]]" in it }) throw IOException("fixture retrieval failure")
                        return texts.map { text ->
                            if ("[[rag-abstain]]" in text || "fixture-no-relevant-context" in text) {
                                EmbeddingVector(List(descriptor.dimensions) { 0f })
                            } else if ("Текущий вопрос:" in text &&
                                ("документного rag" in text.lowercase() || "structure-aware" in text.lowercase() ||
                                    "descriptor embedding" in text.lowercase())) {
                                delegate.embed(listOf(RAG_FIXTURE_INDEXING_TEXT)).single()
                            } else if ("Текущий вопрос:" in text &&
                                ("backup" in text.lowercase() || "копи" in text.lowercase() || "restore drill" in text.lowercase())) {
                                delegate.embed(listOf(RAG_FIXTURE_BACKUP_TEXT)).single()
                            } else if ("Текущий вопрос:" in text) {
                                delegate.embed(listOf(RAG_FIXTURE_RELEASE_TEXT)).single()
                            } else if ("Как запустить production web-приложение?" in text) {
                                delegate.embed(listOf(RAG_FIXTURE_RUN_WEB_TEXT)).single()
                            } else if ("AUTH_CLOCK_SKEW" in text || "авторизац" in text.lowercase()) {
                                delegate.embed(listOf(RAG_FIXTURE_SUPPORT_TEXT)).single()
                            } else if ("Как устроен проект и какая сейчас Git-ветка?" in text ||
                                "Объясни архитектуру проекта, как запустить web-интерфейс" in text) {
                                delegate.embed(listOf(RAG_FIXTURE_RUN_WEB_TEXT)).single()
                            } else {
                                delegate.embed(listOf(text)).single()
                            }
                        }
                    }
                }
            }
        },
        clientFactory = { _, _, model -> FixtureLlmClient(model) }, persistSettings = store::save)
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { workbenchModule(WorkbenchApi(controller), LocalAccess(port)) }
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking { controller.shutdown() }
        server.stop(0, 1000)
        directory.toFile().deleteRecursively()
    })
    server.start(wait = false)
    println("LLM Workbench test fixture → http://127.0.0.1:$port (deterministic LLM, temporary .env)")
    Thread.currentThread().join()
}

private class FixtureLlmClient(private val model: String) : LlmClient {
    override fun stream(messages: List<LlmMessage>, options: CompletionOptions): Flow<CompletionEvent> = flow {
        val completion = complete(messages, options)
        val prompt = messages.lastOrNull { it.role == LlmRole.USER }?.content.orEmpty()
        val chunks = if ("[[stream]]" in prompt) {
            completion.content.chunked(7)
        } else {
            completion.content.chunked((completion.content.length / 6).coerceAtLeast(1))
        }
        chunks.forEach { chunk ->
            emit(TextDelta(chunk))
            delay(if ("[[stream]]" in prompt) 120 else 5)
        }
        emit(CompletionFinished(completion.finishReason, completion.usage, completion.model, completion.toolCalls))
    }

    override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
        val prompt = messages.lastOrNull { it.role == LlmRole.USER }?.content.orEmpty()
        val toolResult = messages.lastOrNull { it.role == LlmRole.TOOL }
        val assistantProfile = messages.firstOrNull { it.role == LlmRole.SYSTEM }?.content.orEmpty()
        delay(
            when {
                "[[slow]]" in prompt -> 2500
                "[[stream]]" in prompt -> 500
                else -> 180
            },
        )
        if ("[[network]]" in prompt) throw IOException("fixture network failure")
        if ("[[rewrite-error]]" in prompt && "Перепиши исходный вопрос" in assistantProfile) {
            throw LlmApiException("Fixture rewrite error")
        }
        if ("[[baseline-error]]" in prompt && "предоставленный контекст" !in assistantProfile) {
            throw LlmApiException("Fixture baseline error")
        }
        if ("[[rag-error]]" in prompt &&
            ("предоставленный контекст" in assistantProfile || "retrieval-augmented generation" in assistantProfile)) {
            throw LlmApiException("Fixture RAG error")
        }
        if ("[[partial]]" in prompt && model == "gpt-5.6-terra") throw LlmApiException("Модель временно недоступна")
        val pipelineResults = messages.filter { it.role == LlmRole.TOOL && it.name in PIPELINE_TOOL_NAMES }
            .associateBy { requireNotNull(it.name) }
        val projectResults = messages.filter { it.role == LlmRole.TOOL && it.name in PROJECT_FILE_TOOL_NAMES }
            .associateBy { requireNotNull(it.name) }
        if (prompt in PROJECT_FILE_FIXTURE_PROMPTS && options.tools.any { it.name == PROJECT_SEARCH_TOOL } &&
            PROJECT_SEARCH_TOOL !in projectResults) {
            return fixtureToolCall(
                id = "fixture-project-search",
                name = PROJECT_SEARCH_TOOL,
                arguments = buildJsonObject { put("query", "FixtureProjectApi") },
            )
        }
        if (prompt in PROJECT_FILE_FIXTURE_PROMPTS && PROJECT_SEARCH_TOOL in projectResults &&
            PROJECT_READ_FILES_TOOL !in projectResults) {
            val searchResult = parseFixtureToolResult(requireNotNull(projectResults[PROJECT_SEARCH_TOOL]))
            val paths = searchResult.getValue("matches").jsonArray
                .map { it.jsonObject.getValue("path").jsonPrimitive.content }
                .distinct()
                .take(3)
            check(paths.size == 3) { "Fixture project search did not discover three related files" }
            return fixtureToolCall(
                id = "fixture-project-read",
                name = PROJECT_READ_FILES_TOOL,
                arguments = buildJsonObject { putJsonArray("paths") { paths.forEach(::add) } },
            )
        }
        if (prompt == PROJECT_FILE_UPDATE_PROMPT && PROJECT_READ_FILES_TOOL in projectResults &&
            PROJECT_WRITE_FILE_TOOL !in projectResults) {
            val readResult = parseFixtureToolResult(requireNotNull(projectResults[PROJECT_READ_FILES_TOOL]))
            val files = readResult.getValue("files").jsonArray.map(JsonElement::jsonObject)
            check(files.any { it.getValue("content").jsonPrimitive.content.contains("get(FixtureProjectApi.ROUTE)") })
            check(files.any { it.getValue("content").jsonPrimitive.content.contains("method: \"GET\"") })
            val documentation = files.single {
                it.getValue("path").jsonPrimitive.content == FIXTURE_PROJECT_DOCUMENTATION_PATH
            }
            check(documentation.getValue("content").jsonPrimitive.content.contains("POST"))
            return fixtureToolCall(
                id = "fixture-project-write",
                name = PROJECT_WRITE_FILE_TOOL,
                arguments = buildJsonObject {
                    put("path", FIXTURE_PROJECT_DOCUMENTATION_PATH)
                    put("content", FIXTURE_PROJECT_DOCUMENTATION_CURRENT)
                    put("mode", "apply")
                    put("createOnly", false)
                    put("expectedSha256", documentation.getValue("sha256"))
                },
            )
        }
        if (prompt == PIPELINE_FIXTURE_PROMPT && options.tools.any { it.name == SEARCH_TOOL } && SEARCH_TOOL !in pipelineResults) {
            return fixtureToolCall(
                id = "fixture-pipeline-search",
                name = SEARCH_TOOL,
                arguments = buildJsonObject { put("query", "композиция MCP-инструментов") },
            )
        }
        if (prompt == PIPELINE_FIXTURE_PROMPT && SEARCH_TOOL in pipelineResults && SUMMARIZE_TOOL !in pipelineResults) {
            val searchResult = parseFixtureToolResult(requireNotNull(pipelineResults[SEARCH_TOOL]))
            return fixtureToolCall(
                id = "fixture-pipeline-summarize",
                name = SUMMARIZE_TOOL,
                arguments = buildJsonObject {
                    put("matches", requireNotNull(searchResult["matches"]))
                    put("maxSentences", 3)
                },
            )
        }
        if (prompt == PIPELINE_FIXTURE_PROMPT && SUMMARIZE_TOOL in pipelineResults && SAVE_TO_FILE_TOOL !in pipelineResults) {
            val summaryResult = parseFixtureToolResult(requireNotNull(pipelineResults[SUMMARIZE_TOOL]))
            return fixtureToolCall(
                id = "fixture-pipeline-save",
                name = SAVE_TO_FILE_TOOL,
                arguments = buildJsonObject {
                    put("fileName", "pipeline-summary.md")
                    put("content", requireNotNull(summaryResult["summary"]))
                    put("sourceIds", requireNotNull(summaryResult["sourceIds"]))
                },
            )
        }
        if (options.tools.any { it.name == "scheduler_create" } &&
            ("Напомни через 10 минут" in prompt || "Напомни через секунду" in prompt || "[[schedule-reminder]]" in prompt) && toolResult == null) {
            val quick = "Напомни через секунду" in prompt
            return CompletionResult(
                content = "",
                finishReason = "tool_calls",
                usage = TokenUsage(38, 14, 52),
                model = model,
                toolCalls = listOf(LlmToolCall(
                    id = "fixture-scheduler-call-1",
                    name = "scheduler_create",
                    arguments = buildJsonObject {
                        put("requestId", "fixture-${prompt.hashCode().toUInt()}")
                        put("title", if (quick) "Быстрое напоминание" else "Проверить DEMO-101")
                        put("scheduleType", "once")
                        put("runAt", Instant.now().plusSeconds(if (quick) 1 else 600).toString())
                        put("taskType", "reminder")
                        put("reminderText", if (quick) "Проверить фоновое выполнение" else "Проверить DEMO-101")
                    }.toString(),
                )),
            )
        }
        if (options.tools.any { it.name == "scheduler_create" } &&
            "Каждые 30 минут" in prompt && toolResult == null) {
            return CompletionResult(
                content = "",
                finishReason = "tool_calls",
                usage = TokenUsage(42, 14, 56),
                model = model,
                toolCalls = listOf(LlmToolCall(
                    id = "fixture-scheduler-call-2",
                    name = "scheduler_create",
                    arguments = buildJsonObject {
                        put("requestId", "fixture-${prompt.hashCode().toUInt()}")
                        put("title", "Снимки DEMO-101")
                        put("scheduleType", "fixed_interval")
                        put("everySeconds", 1800)
                        put("taskType", "tracker_snapshot")
                        put("issueId", "DEMO-101")
                    }.toString(),
                )),
            )
        }
        if (options.tools.any { it.name == "tracker_get_issue" } && "DEMO-101" in prompt && toolResult == null) {
            return CompletionResult(
                content = "",
                finishReason = "tool_calls",
                usage = TokenUsage(35, 12, 47),
                model = model,
                toolCalls = listOf(LlmToolCall(
                    id = "fixture-tracker-call-1",
                    name = "tracker_get_issue",
                    arguments = "{\"issueId\":\"DEMO-101\",\"includeComments\":false}",
                )),
            )
        }
        fun taskField(name: String) = Regex("\\\"$name\\\":\\\"([^\\\"]*)\\\"")
            .find(assistantProfile)?.groupValues?.get(1).orEmpty()
        val taskPhase = taskField("phase")
        val invariantId = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"")
            .find(assistantProfile.substringAfter("ASSISTANT INVARIANTS", ""))?.groupValues?.get(1).orEmpty()
        val invariantCategory = Regex("\\\"category\\\":\\\"([^\\\"]+)\\\"")
            .find(assistantProfile.substringAfter("ASSISTANT INVARIANTS", ""))?.groupValues?.get(1).orEmpty()
        val answer = when {
            "Перепиши исходный вопрос" in assistantProfile ->
                if ("[[rag-abstain]]" in prompt) "fixture-no-relevant-context" else
                    prompt.replace(Regex("\\[\\[[^]]+]]"), "").trim() + " локальная техническая документация"
            "предоставленный контекст" in assistantProfile && "Контекст:" in prompt ->
                groundedRagFixtureAnswer(
                    prompt,
                    "Production web-приложение запускается командой `./gradlew runWeb` [S1].",
                )
            DEVELOPER_HELP_SYSTEM_PROMPT in assistantProfile && "REPOSITORY DOCUMENT EVIDENCE" in prompt -> {
                val branch = Regex("\\\"branch\\\":\\\"([^\\\"]+)\\\"")
                    .find(prompt)?.groupValues?.get(1) ?: "не определена"
                groundedAssistantRagFixtureAnswer(
                    prompt,
                    "Проект использует Kotlin/Ktor backend и React frontend; production web запускается командой `./gradlew runWeb` [S1]. " +
                        "Текущая Git-ветка: `$branch`.",
                )
            }
            SUPPORT_ASSISTANT_SYSTEM_PROMPT in assistantProfile && "SUPPORT TICKET MCP DATA" in prompt ->
                groundedAssistantRagFixtureAnswer(
                    prompt,
                    """Для тикета TCK-1001 наиболее вероятна причина, подтверждённая кодом AUTH_CLOCK_SKEW: системное время устройства расходится с доверенным временем сервиса [S1].

Что видно в тикете
Тикет TCK-1001 открыт для USR-1001; аккаунт ACTIVE, MFA включён, версия клиента 3.8.1. Последнее событие сообщает о расхождении времени на 742 секунды.

Что сделать
1. Включить автоматические дату, время и часовой пояс и синхронизировать устройство [S1].
2. Перезапустить клиент и выполнить новый вход с новым MFA-кодом [S1].
3. Если ошибка сохранится, эскалировать тикет с кодом ошибки, версией клиента и временем события [S1].""",
                )
            "retrieval-augmented generation" in assistantProfile && "RETRIEVED DOCUMENT EVIDENCE" in prompt ->
                groundedAssistantRagFixtureAnswer(
                    prompt,
                    "Контекстный RAG-ответ подтверждён локальным источником [S1].",
                )
            prompt == PIPELINE_FIXTURE_PROMPT && SAVE_TO_FILE_TOOL in pipelineResults -> {
                val saved = parseFixtureToolResult(requireNotNull(pipelineResults[SAVE_TO_FILE_TOOL]))
                val fileName = saved["fileName"]?.jsonPrimitive?.content.orEmpty()
                "Локальные сведения о композиции MCP-инструментов найдены, кратко суммированы и сохранены в `$fileName`."
            }
            prompt == PROJECT_FILE_ANALYSIS_PROMPT && PROJECT_READ_FILES_TOOL in projectResults -> {
                val read = parseFixtureToolResult(requireNotNull(projectResults[PROJECT_READ_FILES_TOOL]))
                val paths = read.getValue("files").jsonArray
                    .map { it.jsonObject.getValue("path").jsonPrimitive.content }
                check(paths.size == 3)
                """Найдены все 4 вхождения FixtureProjectApi; поиск не был усечён.

Путь вызова: `${paths.single { it.endsWith("FixtureProjectApi.kt") }}` задаёт Kotlin-константу `/api/fixture-project` и GET route. `${paths.single { it.endsWith("fixtureProjectApi.ts") }}` экспортирует TypeScript-клиент, который делает GET по тому же пути. `${paths.single { it.endsWith("fixture-project-api.md") }}` описывает контракт, но сейчас ошибочно указывает POST.

Просмотрены файлы:
${paths.joinToString("\n") { "- `$it`" }}

Ничего не изменено."""
            }
            prompt == PROJECT_FILE_UPDATE_PROMPT && PROJECT_WRITE_FILE_TOOL in projectResults -> {
                val write = parseFixtureToolResult(requireNotNull(projectResults[PROJECT_WRITE_FILE_TOOL]))
                val path = write.getValue("path").jsonPrimitive.content
                val applied = write.getValue("applied").jsonPrimitive.boolean
                val diff = write.getValue("diff").jsonPrimitive.content
                """Документация сверена с Kotlin и TypeScript реализацией и ${if (applied) "обновлена" else "не потребовала повторной записи"}.

Просмотрены файлы:
- `src/main/kotlin/fixture/FixtureProjectApi.kt`
- `frontend/src/api/fixtureProjectApi.ts`
- `$FIXTURE_PROJECT_DOCUMENTATION_PATH`

Изменённый файл: `$path`. Применено: $applied.

```diff
$diff
```"""
            }
            toolResult?.name == "scheduler_create" && "schedules" in toolResult.content ->
                "Фоновая задача создана и сохранена локально. Статус и следующее выполнение уже доступны в панели «Фоновые задачи»."
            toolResult?.name == "tracker_get_issue" && "DEMO-101" in toolResult.content ->
                "Задача **DEMO-101** имеет статус **In Progress**. Следующее действие: завершить сквозные тесты и отправить изменение на проверку."
            messages.firstOrNull()?.content?.contains("key-value memory") == true -> {
                val value = Regex("меня зовут\\s+([\\p{L}-]+)", RegexOption.IGNORE_CASE).find(prompt)?.groupValues?.get(1)
                if (value != null) "{\"upsert\":{\"name\":\"$value\"},\"delete\":[]}" else "{\"upsert\":{},\"delete\":[]}"
            }
            "Начни реализацию" in prompt && taskPhase == "PLANNING" ->
                "Переход сейчас недопустим: текущая фаза PLANNING. Сначала явно утвердите план действием «Утвердить план и начать выполнение»."
            "готовой без проверки" in prompt && taskPhase != "DONE" ->
                "UNSAFE TASK OUTPUT: задача официально готова, считаем дело закрытым."
            "Продолжай" in prompt && "TASK STATE DATA" in assistantProfile ->
                "Сохранённая задача: цель=${taskField("goal")}; этап=${taskField("phase")}; шаг=${taskField("currentStep")}; следующее действие=${taskField("expectedAction")}."
            "[[invalid-invariant-receipt]]" in prompt ->
                "UNSAFE MODEL OUTPUT: invariant protocol was ignored"
            "endpoint /health" in prompt && "ASSISTANT INVARIANTS" in assistantProfile ->
                "Endpoint `/health` добавлен на Kotlin и возвращает статус приложения."
            "Python" in prompt && "ASSISTANT INVARIANTS" in assistantProfile && "endpoint проверки здоровья" in prompt ->
                "Python-часть выполнить нельзя: конфликтующий инвариант $invariantId ($invariantCategory) требует сохранить Kotlin/JVM 21, поэтому смена стека нарушает правило. Совместимая часть выполнена: endpoint проверки здоровья добавлен на Kotlin."
            "Python" in prompt && "ASSISTANT INVARIANTS" in assistantProfile ->
                "Запрос в предложенном виде выполнить нельзя. Конфликтующий инвариант: $invariantId ($invariantCategory). Python напрямую нарушает обязательное правило сохранить backend на Kotlin/JVM 21. Совместимая альтернатива — реализовать изменение в текущем Kotlin-стеке."
            "Ответь таблицей" in prompt -> "| Формат | Ответ |\n|---|---|\n| Явный запрос | Таблица |"
            "\"responseFormat\":\"Маркированный список\"" in assistantProfile ->
                "- Резервная копия хранит запасной набор данных.\n- Проверяйте восстановление регулярно."
            "\"responseStyle\":\"Подробно, с техническими терминами\"" in assistantProfile ->
                "Резервное копирование — это связный технический процесс: полная и инкрементальная стратегия создают точки восстановления, а проверка целостности и retention policy управляют жизненным циклом копий."
            "[[stream]]" in prompt -> "# Потоковый заголовок\n\n**Жирный текст**\n\n```kotlin\nval answer = 42\n```"
            "Составь эффективный промпт" in prompt -> "Реши задачу о 100 шкафчиках, проверь число делителей и укажи все полные квадраты."
            "независимый оценщик" in prompt -> """
                ## Итоговая оценка
                | Вариант | Точность | Вывод |
                | :--- | ---: | :--- |
                | A | 10 | Верный и ясный ответ |
                | B | 9 | Верно, можно короче |
                | C | 10 | Подробная проверка |

                **Вывод:** сравнивайте качество по задаче. Один прогон не даёт статистического вывода.
            """.trimIndent()
            "шкафчик" in prompt || "шкафчиков" in prompt -> "Открыты **10 шкафчиков**: 1, 4, 9, 16, 25, 36, 49, 64, 81, 100. Нечётное число делителей имеют только полные квадраты."
            "[[long]]" in prompt -> buildString {
                appendLine("# Длинный ответ для проверки оформления")
                repeat(18) { index ->
                    appendLine("## Раздел ${index + 1}\n")
                    appendLine("Текст с **выделением**, списками и [ссылкой](https://example.com). Сравнение остаётся читаемым даже при большом объёме данных.\n")
                    appendLine("| Модель | Точность | Детали | Время | Токены | Комментарий |\n|---|---:|---|---:|---:|---|\n| Luna | 10 | Строка один<br>Строка два: длинное описание результатов для проверки прокрутки | 1.2 | 125 | Хороший результат |\n")
                    appendLine("```kotlin\nfun square(x: Int) = x * x\n```\n")
                    appendLine("\\[ \\frac{1}{n} \\sum_{i=1}^{n} x_i^2 \\]\n")
                }
                appendLine("<script>window.injected = true</script><img src=x onerror=alert(1)>")
            }
            "temperature" in prompt || "слогана" in prompt -> "Результат: 22\n\n- Орбита — ближе к звёздам\n- Орбита открывает космос\n- Твоя Орбита вдохновения"
            "Требования к ответу:" in prompt -> "- Кубит описывает квантовое состояние.\n- Алгоритм меняет вероятности измерений.\n- Измерение возвращает классический результат."
            else -> "## Ответ\n\nКвантовый компьютер использует **кубиты** и квантовые операции. Интерференция помогает усилить вероятность нужного результата.\n\n| Подход | Контекст |\n|---|---|\n| Текущий запрос | ${messages.size} сообщений |\n\nФормула: \\( E = mc^2 \\)."
        }
        val content = if ("ASSISTANT INVARIANTS" in assistantProfile && "[[invalid-invariant-receipt]]" !in prompt) {
            val invariantBlock = assistantProfile.substringAfter("ASSISTANT INVARIANTS", "")
                .substringBefore("END ASSISTANT INVARIANTS")
            val stateVersion = Regex("\\\"stateVersion\\\":(\\d+)")
                .find(invariantBlock)?.groupValues?.get(1)?.toLong() ?: 0L
            val ids = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"")
                .findAll(invariantBlock).map { it.groupValues[1] }.toList()
            val conflicts = if ("Python" in prompt) ids else emptyList()
            val receipt = buildJsonObject {
                put("stateVersion", stateVersion)
                put("checkedInvariantIds", buildJsonArray { ids.forEach(::add) })
                put("conflictingInvariantIds", buildJsonArray { conflicts.forEach(::add) })
                put("decision", if (conflicts.isEmpty()) "COMPATIBLE" else "CONFLICT")
                put("answer", answer)
                put("audit", buildJsonObject {
                    put("stateVersion", stateVersion)
                    put("checkedInvariantIds", buildJsonArray { ids.forEach(::add) })
                    put("violatedInvariantIds", buildJsonArray { })
                    put("answerCompliant", true)
                })
            }
            receipt.toString()
        } else answer
        return CompletionResult(content, if (options.maxTokens != null && options.maxTokens < 50) "length" else "stop",
            TokenUsage(120, 80, 200, cachedPromptTokens = 20, cacheWritePromptTokens = 5, reasoningTokens = 10),
            if (model == "gpt-5.6-luna" && options.temperature != null) "gpt-4.1-mini" else model)
    }

    private fun fixtureToolCall(id: String, name: String, arguments: JsonObject) = CompletionResult(
        content = "",
        finishReason = "tool_calls",
        usage = TokenUsage(36, 12, 48),
        model = model,
        toolCalls = listOf(LlmToolCall(id = id, name = name, arguments = arguments.toString())),
    )
}

private fun parseFixtureToolResult(message: LlmMessage): JsonObject =
    Json.parseToJsonElement(message.content.substringAfter('\n')).jsonObject

private const val PIPELINE_FIXTURE_PROMPT =
    "Найди локальные сведения о композиции MCP-инструментов, кратко суммируй их и сохрани в pipeline-summary.md"
private val PIPELINE_TOOL_NAMES = setOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TO_FILE_TOOL)

private const val PROJECT_FILE_ANALYSIS_PROMPT =
    "Найди все места использования API FixtureProjectApi в проекте, прочитай связанные файлы и объясни путь вызова от Kotlin до TypeScript. Ничего не изменяй."
private const val PROJECT_FILE_UPDATE_PROMPT =
    "Проверь, соответствует ли документация FixtureProjectApi фактической реализации. Если она устарела, обнови её и покажи diff."
private val PROJECT_FILE_FIXTURE_PROMPTS = setOf(PROJECT_FILE_ANALYSIS_PROMPT, PROJECT_FILE_UPDATE_PROMPT)
private val PROJECT_FILE_TOOL_NAMES = setOf(PROJECT_SEARCH_TOOL, PROJECT_READ_FILES_TOOL, PROJECT_WRITE_FILE_TOOL)
private const val FIXTURE_PROJECT_DOCUMENTATION_PATH = "docs/fixture-project-api.md"
private const val FIXTURE_PROJECT_DOCUMENTATION_CURRENT = """# FixtureProjectApi

`FixtureProjectApi` exposes `GET /api/fixture-project` from the Kotlin route.
The TypeScript client sends a GET request to the same path and returns the JSON summary.
"""

private fun createProjectFileAssistantFixture(directory: java.nio.file.Path) {
    val kotlin = directory.resolve("src/main/kotlin/fixture/FixtureProjectApi.kt")
    val typescript = directory.resolve("frontend/src/api/fixtureProjectApi.ts")
    val documentation = directory.resolve(FIXTURE_PROJECT_DOCUMENTATION_PATH)
    listOf(kotlin, typescript, documentation).forEach { Files.createDirectories(it.parent) }
    Files.writeString(kotlin, """package fixture

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

object FixtureProjectApi {
    const val ROUTE = "/api/fixture-project"
}

fun Route.fixtureProjectRoutes() {
    get(FixtureProjectApi.ROUTE) { call.respondText("fixture summary") }
}
""")
    Files.writeString(typescript, """export const FixtureProjectApi = {
  path: "/api/fixture-project",
  method: "GET",
  load: () => fetch("/api/fixture-project").then((response) => response.text()),
};
""")
    Files.writeString(documentation, """# FixtureProjectApi

`FixtureProjectApi` accepts `POST /api/fixture-project` and returns a fixture summary.
""")
}
