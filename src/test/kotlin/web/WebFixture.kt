package org.example.web

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.agent.*
import org.example.app.AppSettings
import org.example.app.WorkbenchController
import org.example.config.LocalConfigStore
import org.example.llm.*
import org.example.mcp.LocalMcpGateway
import java.io.IOException
import java.nio.file.Files

/** Deliberately in test sources: no fixture switch or fake client in the production artifact. */
fun main() {
    val port = System.getenv("WEB_PORT")?.toInt() ?: 18080
    val directory = Files.createTempDirectory("workbench-browser-test")
    val store = LocalConfigStore(directory.resolve(".env"), emptyMap())
    val controller = WorkbenchController(AppSettings(LlmKind.OPENAI),
        mapOf(LlmKind.OPENAI to "fixture-openai-key", LlmKind.DEEPSEEK to "fixture-deepseek-key"),
        historyStore = JsonConversationHistoryStore(directory.resolve(".llm-history.json")),
        contextStateStore = JsonContextStateStore(directory.resolve(".llm-context-state.json")),
        assistantMemoryStore = JsonAssistantMemoryStore(directory.resolve(".llm-assistant-memory.json")),
        assistantInvariantStore = JsonAssistantInvariantStore(directory.resolve(".llm-assistant-invariants.json")),
        taskStateStore = JsonTaskStateStore(directory.resolve(".llm-task-state.json")),
        mcpGateway = LocalMcpGateway(),
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
        if ("[[partial]]" in prompt && model == "gpt-5.6-terra") throw LlmApiException("Модель временно недоступна")
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
}
