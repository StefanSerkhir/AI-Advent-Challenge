package org.example.web

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.example.app.AppSettings
import org.example.app.WorkbenchController
import org.example.config.LocalConfigStore
import org.example.llm.*
import java.io.IOException
import java.nio.file.Files

/** Deliberately in test sources: no fixture switch or fake client in the production artifact. */
fun main() {
    val port = System.getenv("WEB_PORT")?.toInt() ?: 18080
    val directory = Files.createTempDirectory("workbench-browser-test")
    val store = LocalConfigStore(directory.resolve(".env"), emptyMap())
    val controller = WorkbenchController(AppSettings(LlmKind.OPENAI),
        mapOf(LlmKind.OPENAI to "fixture-openai-key", LlmKind.DEEPSEEK to "fixture-deepseek-key"),
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
        val chunks = if ("[[stream]]" in messages.last().content) {
            completion.content.chunked(7)
        } else {
            completion.content.chunked((completion.content.length / 6).coerceAtLeast(1))
        }
        chunks.forEach { chunk ->
            emit(TextDelta(chunk))
            delay(if ("[[stream]]" in messages.last().content) 120 else 5)
        }
        emit(CompletionFinished(completion.finishReason, completion.usage, completion.model))
    }

    override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
        val prompt = messages.last().content
        delay(if ("[[slow]]" in prompt) 2500 else 180)
        if ("[[network]]" in prompt) throw IOException("fixture network failure")
        if ("[[partial]]" in prompt && model == "gpt-5.6-terra") throw LlmApiException("Модель временно недоступна")
        val content = when {
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
        return CompletionResult(content, if (options.maxTokens != null && options.maxTokens < 50) "length" else "stop",
            TokenUsage(120, 80, 200, cachedPromptTokens = 20, reasoningTokens = 10),
            if (model == "gpt-5.6-luna" && options.temperature != null) "gpt-4.1-mini" else model)
    }
}
