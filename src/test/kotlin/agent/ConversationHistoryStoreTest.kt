package org.example.agent

import org.example.llm.LlmMessage
import org.example.llm.LlmRole
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
            assertContains(json, "\"version\": 1")
            assertContains(json, "\"id\": \"unrestricted\"")
            assertContains(json, "\"role\": \"user\"")
            assertFalse("api_key" in json)
        } finally {
            directory.toFile().deleteRecursively()
        }
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
