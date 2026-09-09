package org.example.app

import org.example.agent.AgentRequest
import org.example.agent.LlmAgent
import org.example.llm.*
import org.example.tokens.*

enum class TokenDemoScenario(val apiValue: String, val title: String) {
    SHORT("tokens-short", "Токены и контекст · короткий диалог"),
    LONG("tokens-long", "Токены и контекст · длинный диалог"),
    OVERFLOW("tokens-overflow", "Токены и контекст · переполнение (симуляция 6K)"),
}

data class TokenDemoTurn(
    val id: String,
    val title: String,
    val content: String?,
    val metrics: TurnTokenMetrics,
    val error: String? = null,
)

data class TokenContextDemoReport(
    val scenario: TokenDemoScenario,
    val turns: List<TokenDemoTurn>,
    val sentinelInPermanentHistory: Boolean? = null,
    val sentinelInActiveContext: Boolean? = null,
)

/** Fully local deterministic experiment: it never opens a network connection. */
class TokenContextDemoRunner(
    private val estimator: TokenEstimator = ApproximateChatTokenEstimator(),
) {
    suspend fun run(scenario: TokenDemoScenario): TokenContextDemoReport = when (scenario) {
        TokenDemoScenario.SHORT -> conversation(scenario, 4, promptSize = 1)
        TokenDemoScenario.LONG -> conversation(scenario, 14, promptSize = 8)
        TokenDemoScenario.OVERFLOW -> overflow()
    }

    private suspend fun conversation(
        scenario: TokenDemoScenario,
        turnCount: Int,
        promptSize: Int,
    ): TokenContextDemoReport {
        val client = DeterministicTokenDemoClient(estimator)
        val agent = LlmAgent(
            tokenEstimator = estimator,
            profileProvider = { ModelContextProfiles.GPT_5_6_SOL },
            clientProvider = { client },
        )
        val turns = (1..turnCount).map { number ->
            val prompt = buildString {
                append("Ход $number. Запомни число ${number * 7} и кратко объясни рост контекста.")
                repeat(promptSize) { append(" Полная история снова входит в следующий API input.") }
            }
            val response = agent.respond(
                AgentRequest(prompt, CompletionOptions(maxTokens = 220), model = "gpt-5.6-sol"),
            )
            TokenDemoTurn("${scenario.apiValue}-$number", "Ход $number", response.content, response.tokenMetrics)
        }
        return TokenContextDemoReport(scenario, turns)
    }

    private suspend fun overflow(): TokenContextDemoReport {
        val sentinel = "SENTINEL-ALPHA: ранний факт — код сейфа 314159."
        val filler = "Контекстная запись с предсказуемым объёмом. ".repeat(28)
        val initialHistory = buildList {
            add(LlmMessage(LlmRole.USER, sentinel))
            add(LlmMessage(LlmRole.ASSISTANT, "Факт сохранён."))
            repeat(13) { index ->
                add(LlmMessage(LlmRole.USER, "Старый вопрос ${index + 1}. $filler"))
                add(LlmMessage(LlmRole.ASSISTANT, "Старый ответ ${index + 1}. $filler"))
            }
        }
        val profile = ModelContextProfiles.demo()
        var rejectedMetrics: TurnTokenMetrics? = null
        val rejectAgent = LlmAgent(
            initialHistory = initialHistory,
            tokenEstimator = estimator,
            profileProvider = { profile },
            clientProvider = { error("REJECT не должен запрашивать модель") },
        )
        val current = "Какой ранний контрольный факт был в самом начале?"
        val rejected = try {
            rejectAgent.respond(
                AgentRequest(current, CompletionOptions(maxTokens = 700), model = "gpt-5.6-sol",
                    overflowPolicy = ContextOverflowPolicy.REJECT),
                onMetrics = { rejectedMetrics = it },
            )
            error("Demo history unexpectedly fit the reduced context window")
        } catch (error: ContextLimitExceededException) {
            TokenDemoTurn("tokens-overflow-reject", "REJECT · локальное отклонение", null,
                requireNotNull(rejectedMetrics), error.message)
        }

        val dropClient = DeterministicTokenDemoClient(estimator)
        val dropAgent = LlmAgent(
            initialHistory = initialHistory,
            tokenEstimator = estimator,
            profileProvider = { profile },
            clientProvider = { dropClient },
        )
        val droppedResponse = dropAgent.respond(
            AgentRequest(current, CompletionOptions(maxTokens = 700), model = "gpt-5.6-sol",
                overflowPolicy = ContextOverflowPolicy.DROP_OLDEST),
        )
        val activeContainsSentinel = dropClient.lastMessages.any { sentinel in it.content }
        val permanentContainsSentinel = dropAgent.historySnapshot().any { sentinel in it.content }
        val dropTurn = TokenDemoTurn(
            "tokens-overflow-drop", "DROP_OLDEST · запрос после сокращения",
            droppedResponse.content + "\n\nСентинел в активном контексте: ${if (activeContainsSentinel) "да" else "нет"}. " +
                "В постоянной истории: ${if (permanentContainsSentinel) "да" else "нет"}.",
            droppedResponse.tokenMetrics,
        )
        return TokenContextDemoReport(TokenDemoScenario.OVERFLOW, listOf(rejected, dropTurn),
            permanentContainsSentinel, activeContainsSentinel)
    }
}

private class DeterministicTokenDemoClient(private val estimator: TokenEstimator) : LlmClient {
    var lastMessages: List<LlmMessage> = emptyList()
        private set

    override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
        lastMessages = messages.toList()
        val inputEstimate = estimator.estimateMessages(messages).tokens
        val output = "Детерминированный ответ: каждый ход повторно отправляет сохранённую историю, поэтому cumulative API input растёт быстрее текущей истории."
        val completion = estimator.estimateContent(output).tokens + 6
        val cached = if (messages.size > 2) minOf(inputEstimate / 8, 64) else 0
        val cacheWrite = if (messages.size > 4) minOf(inputEstimate / 16, 32) else 0
        val actualInput = inputEstimate + 2 // Deliberate provider/estimate delta for the lesson.
        return CompletionResult(
            output,
            if ((options.maxTokens ?: Int.MAX_VALUE) < completion) "length" else "stop",
            TokenUsage(actualInput, completion, actualInput + completion, cached, cacheWrite, reasoningTokens = 6),
            "gpt-5.6-sol-demo-fixture",
        )
    }
}
