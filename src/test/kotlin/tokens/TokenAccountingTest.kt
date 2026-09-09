package org.example.tokens

import kotlinx.coroutines.runBlocking
import org.example.agent.AgentRequest
import org.example.agent.LlmAgent
import org.example.llm.*
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.*

class TokenAccountingTest {
    private val estimator = ApproximateChatTokenEstimator()

    @Test
    fun `current message history context and protocol overhead are distinct estimates`() {
        val history = listOf(
            LlmMessage(LlmRole.USER, "первый вопрос"),
            LlmMessage(LlmRole.ASSISTANT, "первый ответ"),
        )
        val current = LlmMessage(LlmRole.USER, "новый вопрос")
        val result = ContextPreparer(estimator).prepare(history, current, null, 100, ContextOverflowPolicy.REJECT)

        assertEquals(estimator.estimateContent(current.content).tokens, result.estimatedCurrentMessageTokens.tokens)
        assertEquals(estimator.estimateMessages(history).tokens, result.estimatedHistoryTokens.tokens)
        assertEquals(estimator.estimateMessages(history + current).tokens, result.estimatedContextTokens.tokens)
        assertTrue(result.estimatedContextTokens.tokens > history.sumOf { estimator.estimateContent(it.content).tokens } + result.estimatedCurrentMessageTokens.tokens)
        assertFalse(result.estimatedContextTokens.exact)
        assertNotNull(result.estimatedContextTokens.warning)
    }

    @Test
    fun `fallback is deterministic unicode aware and explicitly approximate`() {
        val first = estimator.estimateContent("Hello, мир 👋")
        val second = estimator.estimateContent("Hello, мир 👋")
        assertEquals(first, second)
        assertTrue(first.tokens > 0)
        assertFalse(first.exact)
        assertEquals(ApproximateChatTokenEstimator.ESTIMATOR_ID, first.estimatorId)
    }

    @Test
    fun `cost separates uncached cached cache-write and does not double count reasoning`() {
        val usage = TokenUsage(100, 200, 300, cachedPromptTokens = 10, cacheWritePromptTokens = 20, reasoningTokens = 30)
        val cost = TokenCostCalculator().calculate(usage, ModelContextProfiles.GPT_5_6_SOL)
        // 70*4 + 10*.4 + 20*(4*1.25) + 200*20, per million. Reasoning is inside the 200 output.
        assertEquals(BigDecimal("0.004384"), cost)
    }

    @Test
    fun `high tier applies to the full request only above threshold`() {
        val calculator = TokenCostCalculator()
        val at = calculator.calculate(TokenUsage(272_000, 100, 272_100), ModelContextProfiles.GPT_5_6_SOL)
        val above = calculator.calculate(TokenUsage(272_001, 100, 272_101), ModelContextProfiles.GPT_5_6_SOL)
        assertEquals(BigDecimal("1.09"), at)
        assertEquals(BigDecimal("2.179008"), above)
    }

    @Test
    fun `missing usage or unknown profile has no invented cost`() {
        val calculator = TokenCostCalculator()
        assertNull(calculator.calculate(null, ModelContextProfiles.GPT_5_6_SOL))
        assertNull(calculator.calculate(TokenUsage(1, 1, 2), null))
    }

    @Test
    fun `all selectable models have official versioned profiles and DeepSeek follows UTC price band`() {
        assertNotNull(ModelContextProfiles.find("gpt-5.6-luna"))
        assertNotNull(ModelContextProfiles.find("gpt-5.6-terra"))
        assertNotNull(ModelContextProfiles.find("gpt-5.6-sol"))
        assertNotNull(ModelContextProfiles.find("gpt-4.1-mini"))

        val peak = ModelContextProfiles.find("deepseek-v4-flash", Instant.parse("2026-09-07T02:00:00Z"))
        val offPeak = ModelContextProfiles.find("deepseek-v4-flash", Instant.parse("2026-09-07T05:00:00Z"))
        val weekend = ModelContextProfiles.find("deepseek-v4-flash", Instant.parse("2026-09-06T02:00:00Z"))
        assertEquals(ModelContextProfiles.DEEPSEEK_V4_FLASH_PEAK.id, peak?.id)
        assertEquals(ModelContextProfiles.DEEPSEEK_V4_FLASH_OFF_PEAK.id, offPeak?.id)
        assertEquals(ModelContextProfiles.DEEPSEEK_V4_FLASH_OFF_PEAK.id, weekend?.id)
        assertEquals(BigDecimal("0.0017174"), TokenCostCalculator().calculate(
            TokenUsage(1_000, 1_000, 2_000, cachedPromptTokens = 100), peak,
        ))
    }

    @Test
    fun `invalid numeric usage is rejected`() {
        assertFailsWith<InvalidTokenUsageException> {
            TokenCostCalculator().calculate(TokenUsage(10, 2, 12, cachedPromptTokens = 8, cacheWritePromptTokens = 4), ModelContextProfiles.GPT_5_6_SOL)
        }
    }

    @Test
    fun `cumulative input differs from current history size and money stays exact`() {
        fun turn(number: Int, input: Int, cost: String) = TurnTokenMetrics(
            turnNumber = number, model = "gpt-5.6-sol", userMessage = "u",
            estimatedCurrentMessageTokens = estimator.estimateContent("u"),
            estimatedHistoryTokens = estimator.estimateMessages(emptyList()),
            estimatedContextTokens = estimator.estimateMessages(listOf(LlmMessage(LlmRole.USER, "u"))),
            actualUsage = TokenUsage(input, 10, input + 10),
            contextBudget = ContextBudget(1000, 100, 100, 900, BigDecimal.ONE, 899),
            turnCostUsd = BigDecimal(cost), cumulativeTotals = ConversationTokenTotals(),
            overflowPolicy = ContextOverflowPolicy.REJECT, requiredTokens = 1,
        )
        val totals = aggregateTotals(listOf(turn(1, 100, "0.000001"), turn(2, 200, "0.000002"), turn(3, 300, "0.000003")), 310)
        assertEquals(600, totals.cumulativeApiInputTokens)
        assertEquals(310, totals.currentHistoryTokens)
        assertEquals(BigDecimal("0.000006"), totals.cumulativeCostUsd)
    }

    @Test
    fun `context boundary includes output reserve and reject does not call client`() = runBlocking {
        val contentTokens = estimator.estimateMessages(listOf(LlmMessage(LlmRole.USER, "коротко"))).tokens
        val profile = ModelContextProfiles.demo(contentTokens + 100, 100)
        val atBoundary = ContextPreparer(estimator).prepare(emptyList(), LlmMessage(LlmRole.USER, "коротко"), profile, 100, ContextOverflowPolicy.REJECT)
        assertEquals(0, atBoundary.budget.estimatedRemainingInputTokens)

        var calls = 0
        val tooSmall = profile.copy(contextWindow = profile.contextWindow - 1)
        val agent = LlmAgent(tokenEstimator = estimator, profileProvider = { tooSmall }, clientProvider = {
            calls++; object : LlmClient {
                override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = CompletionResult("bad", "stop", null)
            }
        })
        assertFailsWith<ContextLimitExceededException> {
            agent.respond(AgentRequest("коротко", CompletionOptions(maxTokens = 100), model = "demo"))
        }
        assertEquals(0, calls)
        assertTrue(agent.historySnapshot().isEmpty())
    }

    @Test
    fun `drop oldest removes only intact pairs keeps current and permanent history`() = runBlocking {
        val orphan = LlmMessage(LlmRole.ASSISTANT, "legacy orphan")
        val sentinelUser = LlmMessage(LlmRole.USER, "sentinel " + "старый ".repeat(80))
        val sentinelAssistant = LlmMessage(LlmRole.ASSISTANT, "answer " + "старый ".repeat(80))
        val recentUser = LlmMessage(LlmRole.USER, "recent " + "новый ".repeat(20))
        val recentAssistant = LlmMessage(LlmRole.ASSISTANT, "recent answer")
        val history = listOf(orphan, sentinelUser, sentinelAssistant, recentUser, recentAssistant)
        var sent = emptyList<LlmMessage>()
        val profile = ModelContextProfiles.demo(contextWindow = 180, maxOutputTokens = 40)
        val client = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions): CompletionResult {
                sent = messages
                return CompletionResult("ok", "stop", TokenUsage(30, 2, 32), "gpt-5.6-sol")
            }
        }
        val agent = LlmAgent(initialHistory = history, tokenEstimator = estimator, profileProvider = { profile }, clientProvider = { client })
        val response = agent.respond(AgentRequest("current", CompletionOptions(maxTokens = 40), model = "gpt-5.6-sol", overflowPolicy = ContextOverflowPolicy.DROP_OLDEST))

        assertEquals(orphan, sent.first())
        assertEquals("current", sent.last().content)
        assertTrue(response.tokenMetrics.excludedMessageCount % 2 == 0)
        assertTrue(response.tokenMetrics.excludedMessageCount >= 2)
        assertTrue(agent.historySnapshot().contains(sentinelUser))
        assertEquals(history.size + 2, agent.historySnapshot().size)
    }

    @Test
    fun `unknown model does not invent context limit but still returns actual usage`() = runBlocking {
        val agent = LlmAgent(clientProvider = { object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) =
                CompletionResult("ok", "stop", TokenUsage(8, 3, 11), "custom-model")
        } })
        val result = agent.respond(AgentRequest("hello", model = "custom-model"))
        assertNull(result.tokenMetrics.contextBudget.contextWindow)
        assertEquals(8, result.tokenMetrics.actualUsage?.promptTokens)
        assertNull(result.tokenMetrics.turnCostUsd)
    }
}
