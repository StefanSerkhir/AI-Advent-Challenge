package org.example.app

import org.example.llm.CompletionResult
import org.example.llm.LlmClient

const val DEMO_REASONING_TASK = """
Есть 100 закрытых шкафчиков. Сначала человек №1 меняет состояние каждого
шкафчика (открывает все). Затем человек №2 меняет состояние каждого второго
шкафчика, человек №3 — каждого третьего, и так далее до человека №100.
Какие шкафчики останутся открытыми и сколько их будет?
"""

const val DEMO_REASONING_REFERENCE = """
Открытыми останутся ровно 10 шкафчиков с номерами-полными квадратами:
1, 4, 9, 16, 25, 36, 49, 64, 81 и 100. Состояние шкафчика меняется по одному
разу для каждого делителя его номера; нечётное число делителей бывает только
у полных квадратов.
"""

enum class ReasoningVariant(
    val heading: String,
    val tableLabel: String,
) {
    DIRECT("1. ПРЯМОЙ ОТВЕТ", "прямой ответ"),
    STEP_BY_STEP("2. РЕШЕНИЕ ПОШАГОВО", "пошагово"),
    GENERATED_PROMPT("3. РЕШЕНИЕ ПО СОЗДАННОМУ ПРОМПТУ", "созданный промпт"),
    EXPERT_PANEL("4. ГРУППА ЭКСПЕРТОВ", "группа экспертов"),
}

data class ReasoningSolution(
    val variant: ReasoningVariant,
    val completion: CompletionResult,
) {
    val content: String
        get() = completion.content
}

data class ReasoningReport(
    val task: String,
    val generatedPrompt: String,
    val solutions: List<ReasoningSolution>,
    val evaluation: CompletionResult,
)

data class ReasoningProgress(
    val current: Int,
    val total: Int = TOTAL_REASONING_API_CALLS,
    val label: String,
)

const val TOTAL_REASONING_API_CALLS = 6

class ReasoningRunner(
    private val onProgress: (ReasoningProgress) -> Unit = {},
    private val clientProvider: () -> LlmClient,
) {
    suspend fun compare(
        task: String,
        referenceAnswer: String? = null,
    ): ReasoningReport {
        require(task.isNotBlank()) { "Задача не может быть пустой" }
        val client = clientProvider()

        // У каждого способа независимый контекст. Прямой прогон получает ровно текст задачи.
        val direct = completeStage(client, 1, "Прямой ответ", task)
        val stepByStep = completeStage(client, 2, "Пошаговое решение", withStepByStepInstruction(task))

        val promptDraft = completeStage(client, 3, "Создание промпта", promptGenerationRequest(task))
        val generatedPrompt = promptDraft.content.trim()
        val generatedPromptSolution = completeStage(client, 4, "Решение по созданному промпту", generatedPrompt)

        val expertPanel = completeStage(client, 5, "Группа экспертов", expertPanelRequest(task))

        val solutions = listOf(
            ReasoningSolution(ReasoningVariant.DIRECT, direct),
            ReasoningSolution(ReasoningVariant.STEP_BY_STEP, stepByStep),
            ReasoningSolution(ReasoningVariant.GENERATED_PROMPT, generatedPromptSolution),
            ReasoningSolution(ReasoningVariant.EXPERT_PANEL, expertPanel),
        )
        val evaluation = completeStage(
            client,
            6,
            "Сравнение и оценка точности",
            evaluationRequest(task, referenceAnswer, solutions),
        )

        return ReasoningReport(
            task = task,
            generatedPrompt = generatedPrompt,
            solutions = solutions,
            evaluation = evaluation,
        )
    }

    private suspend fun completeStage(
        client: LlmClient,
        current: Int,
        label: String,
        prompt: String,
    ): CompletionResult {
        onProgress(ReasoningProgress(current = current, label = label))
        return client.complete(prompt)
    }
}

internal fun withStepByStepInstruction(task: String): String = """
    $task

    Решай пошагово.
""".trimIndent()

internal fun promptGenerationRequest(task: String): String = """
    Составь эффективный промпт, который поможет другой языковой модели точно
    решить приведённую ниже задачу. Не решай задачу сам. В готовом промпте
    сохрани все условия, потребуй проверить логику и дать однозначный итог.
    Верни только готовый промпт без комментариев и оформления.

    Задача:
    $task
""".trimIndent()

internal fun expertPanelRequest(task: String): String = """
    $task

    Решите задачу как группа из трёх независимых экспертов:
    1. Аналитик — формализует условие и выводит ответ.
    2. Инженер — ищет конструктивное или алгоритмическое решение.
    3. Критик — решает задачу самостоятельно и проверяет возможные ошибки.

    Дайте отдельное решение от каждого эксперта с заголовками «Аналитик»,
    «Инженер» и «Критик». В конце кратко укажите, совпали ли их выводы.
""".trimIndent()

internal fun evaluationRequest(
    task: String,
    referenceAnswer: String?,
    solutions: List<ReasoningSolution>,
): String {
    val reference = referenceAnswer?.trim()?.let {
        """
        Проверенный эталонный ответ (он не передавался решающим моделям):
        $it
        """.trimIndent()
    } ?: "Эталон не задан: сначала реши задачу самостоятельно и используй своё решение для проверки."

    val answerBlocks = solutions.joinToString("\n\n") { solution ->
        """
        Способ «${solution.variant.tableLabel}»:
        ${solution.content}
        """.trimIndent()
    }

    return """
        Ты — независимый оценщик решений. Сравни четыре ответа на одну задачу.
        Оцени прежде всего правильность итогового ответа и логики, а не длину
        или стиль. Явно укажи:
        1. отличаются ли ответы по выводу и способу рассуждения;
        2. какие ответы точны или содержат ошибки;
        3. какой способ дал наиболее точный результат.
        Если несколько способов одинаково точны, честно укажи ничью.

        Исходная задача:
        $task

        $reference

        Ответы для сравнения:
        $answerBlocks
    """.trimIndent()
}
