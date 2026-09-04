package org.example.config

import org.example.app.AppBootstrap
import org.example.app.AppSettings
import org.example.app.ResponseMode
import org.example.llm.LlmKind
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class LocalConfigStoreTest {

    @Test
    fun `loads and saves desktop settings while preserving unrelated entries`() {
        val directory = createTempDirectory("llm-config-test")
        val envFile = directory.resolve(".env")
        envFile.writeText(
            """
            # Пользовательский комментарий
            custom_option=keep-me
            llm_kind=Deepseek
            llm_api_key=legacy-dummy-key
            deepseek_api_key=dummy-key
            """.trimIndent(),
        )
        val store = LocalConfigStore(envFile, environment = emptyMap())

        try {
            val loaded = store.load()
            assertEquals("Deepseek", loaded.llmKind)
            assertEquals("dummy-key", loaded.deepSeekApiKey)
            assertFalse(loaded.toString().contains("dummy-key"))

            store.save(
                AppSettings(
                    llmKind = LlmKind.OPENAI,
                    model = "gpt-4.1-mini",
                    responseMode = ResponseMode.CONTROLLED,
                    maxTokens = 450,
                    maxWords = 40,
                    bulletCount = 4,
                    stopSequence = null,
                    historyEnabled = false,
                ),
                mapOf(LlmKind.DEEPSEEK to "dummy-key", LlmKind.OPENAI to "other-dummy-key"),
            )

            val savedText = envFile.readText()
            assertContains(savedText, "# Пользовательский комментарий")
            assertContains(savedText, "custom_option=keep-me")
            assertFalse(savedText.lineSequence().any { it.startsWith("llm_api_key=") })
            val reloaded = store.load()
            assertEquals("OpenAI", reloaded.llmKind)
            assertEquals("gpt-4.1-mini", reloaded.model)
            assertEquals("controlled", reloaded.responseMode)
            assertEquals("450", reloaded.maxTokens)
            assertEquals("off", reloaded.stopSequence)
            assertEquals("other-dummy-key", reloaded.openAiApiKey)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `environment overrides file and invalid values fall back safely`() {
        val directory = createTempDirectory("llm-config-test")
        val envFile = directory.resolve(".env")
        envFile.writeText(
            """
            llm_kind=not-a-provider
            max_tokens=-2
            history_enabled=perhaps
            """.trimIndent(),
        )

        try {
            val config = LocalConfigStore(
                envFile,
                environment = mapOf("llm_kind" to "OpenAI"),
            ).load()
            val bootstrap = AppBootstrap.from(config)

            assertEquals(LlmKind.OPENAI, bootstrap.settings.llmKind)
            assertEquals(300, bootstrap.settings.maxTokens)
            assertEquals(true, bootstrap.settings.historyEnabled)
            assertContains(bootstrap.warning.orEmpty(), "max_tokens")
            assertContains(bootstrap.warning.orEmpty(), "history_enabled")
            assertNull(config.apiKey)

            val invalidProvider = AppBootstrap.from(
                LocalConfig(llmKind = "unknown", responseMode = "unknown"),
            )
            assertEquals(LlmKind.DEEPSEEK, invalidProvider.settings.llmKind)
            assertEquals(ResponseMode.COMPARE, invalidProvider.settings.responseMode)
            assertContains(invalidProvider.warning.orEmpty(), "провайдер")
            assertContains(invalidProvider.warning.orEmpty(), "режим")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `model comparison mode always bootstraps with OpenAI selected`() {
        val bootstrap = AppBootstrap.from(
            LocalConfig(
                llmKind = "Deepseek",
                model = "deepseek-v4-flash",
                responseMode = "models",
                openAiApiKey = "openai-dummy-key",
            ),
        )

        assertEquals(ResponseMode.MODEL_COMPARISON, bootstrap.settings.responseMode)
        assertEquals(LlmKind.OPENAI, bootstrap.settings.llmKind)
        assertEquals("gpt-5.6-luna", bootstrap.settings.model)
        assertEquals("openai-dummy-key", bootstrap.apiKeys[LlmKind.OPENAI])
    }
}
