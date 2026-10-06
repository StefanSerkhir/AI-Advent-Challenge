package org.example.cli

import kotlinx.coroutines.runBlocking
import org.example.llm.CompletionOptions
import org.example.llm.ollama.DEFAULT_OLLAMA_MODEL
import org.example.llm.ollama.OllamaLlmClient
import org.example.llm.streamToCompletion
import org.example.network.createHttpClient
import kotlin.system.exitProcess
import kotlin.time.measureTimedValue

private data class LocalDemoPrompt(
    val complexity: String,
    val text: String,
)

private val LOCAL_DEMO_PROMPTS = listOf(
    LocalDemoPrompt("простой", "Ответь одним коротким предложением: что такое локальная LLM?"),
    LocalDemoPrompt(
        "средний, структурированный",
        "У магазина есть 3 коробки по 12 яблок. Продали 17 яблок. Сколько осталось? Дай краткое вычисление и итог.",
    ),
    LocalDemoPrompt(
        "сложный, код",
        "Напиши на Kotlin функцию `fun uniqueSorted(values: List<Int>): List<Int>`, которая удаляет повторы и возвращает числа по возрастанию. Объясни сложность и приведи два граничных примера.",
    ),
)

fun main(): Unit = runBlocking {
    val httpClient = createHttpClient(
        requestTimeoutMillis = 600_000,
        socketTimeoutMillis = 600_000,
    )
    var failed = false
    try {
        val client = OllamaLlmClient(httpClient, DEFAULT_OLLAMA_MODEL)
        LOCAL_DEMO_PROMPTS.forEachIndexed { index, prompt ->
            val measured = measureTimedValue {
                client.streamToCompletion(
                    prompt.text,
                    CompletionOptions(maxTokens = 900),
                )
            }
            val result = measured.value
            check(result.model == DEFAULT_OLLAMA_MODEL) {
                "Ожидалась модель $DEFAULT_OLLAMA_MODEL, получена ${result.model ?: "неизвестная"}."
            }
            val answer = result.content.trim()
            check(answer.isNotEmpty()) { "Ollama вернула пустой ответ для запроса ${index + 1}." }
            println("Запрос ${index + 1}/3 · ${prompt.complexity}")
            println("Модель: ${result.model}")
            println("Длительность: ${measured.duration.inWholeMilliseconds} мс")
            println("Ответ:")
            println(answer)
            println()
        }
    } catch (error: Exception) {
        System.err.println("Локальная демонстрация завершилась ошибкой: ${error.message ?: "неизвестная ошибка"}")
        failed = true
    } finally {
        httpClient.close()
    }
    if (failed) exitProcess(1)
}
