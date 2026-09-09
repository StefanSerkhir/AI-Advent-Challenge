package org.example.agent

import org.example.llm.LlmMessage
import org.example.llm.LlmRole
import org.example.llm.TokenUsage
import org.example.tokens.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class ConversationHistoryStoreTest {
    @Test
    fun `unicode and markdown survive versioned JSON round trip`() {
        val directory = createTempDirectory("llm-history-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        val store = JsonConversationHistoryStore(file)
        val history = linkedMapOf(
            "unrestricted" to listOf(
                LlmMessage(LlmRole.USER, "Привет, мир 👋\n\n`val ответ = 42`"),
                LlmMessage(LlmRole.ASSISTANT, "# Ответ\n\n- **точно**\n- 日本語"),
            ),
            "controlled" to emptyList(),
        )

        try {
            store.save(history)

            assertEquals(history, JsonConversationHistoryStore(file).load())
            val json = file.readText()
            assertContains(json, "\"version\": 2")
            assertContains(json, "\"id\": \"unrestricted\"")
            assertContains(json, "\"role\": \"user\"")
            assertFalse("api_key" in json)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `version two restores turn metrics and exact decimal cost`() {
        val directory = createTempDirectory("llm-history-metrics-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        val estimator = ApproximateChatTokenEstimator()
        val messages = listOf(LlmMessage(LlmRole.USER, "u"), LlmMessage(LlmRole.ASSISTANT, "a"))
        val turn = TurnTokenMetrics(
            id = "stable-turn", turnNumber = 1, model = "gpt-5.6-sol", userMessage = "u", assistantMessage = "a",
            estimatedCurrentMessageTokens = estimator.estimateContent("u"),
            estimatedHistoryTokens = estimator.estimateMessages(emptyList()),
            estimatedContextTokens = estimator.estimateMessages(messages.take(1)),
            actualUsage = TokenUsage(11, 7, 18, 2, 1, 3),
            contextBudget = ContextBudget(1_050_000, 300, 300, 1_049_700, BigDecimal("0.0011"), 1_049_689),
            finishReason = "stop", turnCostUsd = BigDecimal("0.000123456789"),
            cumulativeTotals = ConversationTokenTotals(1, 20, 11, 7, 3, 18, 2, 1, BigDecimal("0.000123456789"), 0),
            overflowPolicy = ContextOverflowPolicy.REJECT, requiredTokens = 11,
            pricingProfileId = ModelContextProfiles.GPT_5_6_SOL.id,
            pricingEffectiveDate = ModelContextProfiles.GPT_5_6_SOL.effectiveDate,
            pricingSourceUrl = ModelContextProfiles.GPT_5_6_SOL.sourceUrl,
        )
        try {
            JsonConversationHistoryStore(file).saveState(ConversationPersistenceSnapshot(mapOf("unrestricted" to messages), mapOf("unrestricted" to listOf(turn))))
            val restored = JsonConversationHistoryStore(file).loadState()
            assertEquals(messages, restored.messages.getValue("unrestricted"))
            assertEquals(turn, restored.turnMetrics.getValue("unrestricted").single())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `legacy version one loads messages without inventing actual metrics`() {
        val directory = createTempDirectory("llm-history-v1-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        try {
            file.writeText("""{"version":1,"branches":[{"id":"unrestricted","messages":[{"role":"user","text":"old"},{"role":"assistant","text":"answer"}]}]}""")
            val restored = JsonConversationHistoryStore(file).loadState()
            assertEquals(listOf("old", "answer"), restored.messages.getValue("unrestricted").map { it.content })
            assertTrue(restored.turnMetrics.getValue("unrestricted").isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `missing file means empty history`() {
        val directory = createTempDirectory("llm-history-test")
        try {
            assertEquals(emptyMap(), JsonConversationHistoryStore(directory.resolve("missing.json")).load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt JSON and unsupported version are rejected`() {
        val directory = createTempDirectory("llm-history-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        try {
            file.writeText("{ definitely-not-json")
            assertFails { JsonConversationHistoryStore(file).load() }

            file.writeText("""{"version":99,"branches":[]}""")
            assertFails { JsonConversationHistoryStore(file).load() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unreadable history path is rejected without reading data as messages`() {
        val directory = createTempDirectory("llm-history-test")
        try {
            assertFails { JsonConversationHistoryStore(directory).load() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save writes a temporary file and replaces target only after complete JSON exists`() {
        val directory = createTempDirectory("llm-history-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        file.writeText("old complete value")
        var observedTemporaryFile = false
        val store = JsonConversationHistoryStore(file) { temporary, target ->
            assertTrue(Files.exists(temporary))
            assertTrue(temporary.fileName.toString().endsWith(".tmp"))
            assertContains(temporary.readText(), "атомарно")
            assertEquals("old complete value", target.readText())
            observedTemporaryFile = true
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }

        try {
            store.save(mapOf("unrestricted" to listOf(LlmMessage(LlmRole.USER, "атомарно"))))

            assertTrue(observedTemporaryFile)
            assertContains(file.readText(), "атомарно")
            assertTrue(Files.list(directory).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed replacement preserves previous file and removes temporary file`() {
        val directory = createTempDirectory("llm-history-test")
        val file = directory.resolve(DEFAULT_HISTORY_FILE_NAME)
        JsonConversationHistoryStore(file).save(
            mapOf("unrestricted" to listOf(LlmMessage(LlmRole.USER, "previous valid message"))),
        )
        val previousFile = file.readText()
        val store = JsonConversationHistoryStore(file) { _, _ -> error("disk failure") }

        try {
            assertFails { store.save(mapOf("unrestricted" to emptyList())) }
            assertEquals(previousFile, file.readText())
            assertEquals("previous valid message", JsonConversationHistoryStore(file).load().getValue("unrestricted").single().content)
            assertTrue(Files.list(directory).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
