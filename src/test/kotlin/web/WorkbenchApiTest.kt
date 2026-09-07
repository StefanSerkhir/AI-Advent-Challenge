package org.example.web

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.example.app.AppSettings
import org.example.app.DEFAULT_STOP_SEQUENCE
import org.example.app.ResponseMode
import org.example.app.WorkbenchController
import org.example.config.LocalConfigStore
import org.example.llm.*
import java.io.IOException
import java.nio.file.Files
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorkbenchApiTest {
    private fun controller(
        mode: ResponseMode = ResponseMode.COMPARE,
        keys: Map<LlmKind, String> = mapOf(LlmKind.OPENAI to "test-secret-key"),
        persist: (AppSettings, Map<LlmKind, String>) -> Unit = { _, _ -> },
        answer: suspend (String, List<LlmMessage>, CompletionOptions) -> CompletionResult = { model, _, _ -> completion(model) },
    ) = WorkbenchController(AppSettings(LlmKind.OPENAI, responseMode = mode), keys,
        clientFactory = { _, _, model -> object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>, options: CompletionOptions) = answer(model, messages, options)
        } }, persistSettings = persist)

    private fun command(version: Long = 0, prompt: String = "Тест", demo: String? = null) = StartCommand(UUID.randomUUID().toString(), version, prompt, demo)
    private fun HttpRequestBuilder.localJson(body: String = "{}") {
        header("X-Workbench-Request", "1")
        header(HttpHeaders.Origin, "http://localhost:8080")
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    private suspend fun HttpResponse.state(): StateDto = apiJson.decodeFromString(bodyAsText())
    private val base = "http://localhost:8080/api"

    @Test
    fun `settings keys validation persistence and secret-free snapshots`() = testApplication {
        engine { connector { host = "localhost"; port = 8080 } }
        val client = createClient { defaultRequest { if (!headers.contains(HttpHeaders.Host)) header(HttpHeaders.Host, "localhost:8080") } }
        val directory = Files.createTempDirectory("api-config")
        val store = LocalConfigStore(directory.resolve(".env"), emptyMap())
        val c = controller(keys = emptyMap(), persist = store::save)
        application { workbenchModule(WorkbenchApi(c)) }
        try {
            val initial = client.get("$base/settings").state()
            assertEquals(6, initial.modes.size)
            assertEquals(2, initial.providers.size)
            assertFalse(initial.providers.any { it.hasKey })
            assertEquals(HttpStatusCode.BadRequest, client.post("$base/operations") { localJson(apiJson.encodeToString(command())) }.status)
            val key = "replacement-secret-xyz"
            val saved = client.put("$base/key") { localJson(apiJson.encodeToString(KeyCommand(0, "OPENAI", key))) }
            assertEquals(HttpStatusCode.OK, saved.status)
            assertFalse(saved.bodyAsText().contains(key))
            assertTrue(saved.state().providers.first { it.id == "OPENAI" }.hasKey)
            assertEquals(key, store.load().openAiApiKey)
            val changed = initial.settings.copy(mode = "controlled", maxTokens = 123, maxWords = 12, bulletCount = 2, stopSequence = "DONE", historyEnabled = false)
            val state = client.put("$base/settings") { localJson(apiJson.encodeToString(SettingsCommand(1, changed))) }.state()
            assertEquals(changed, state.settings)
            assertEquals("123", store.load().maxTokens)
            assertEquals(HttpStatusCode.BadRequest, client.put("$base/settings") { localJson(apiJson.encodeToString(SettingsCommand(2, changed.copy(maxTokens = 0)))) }.status)
            assertEquals(HttpStatusCode.BadRequest, client.put("$base/settings") { localJson(apiJson.encodeToString(SettingsCommand(2, changed.copy(stopSequence = "\n")))) }.status)
            assertEquals(HttpStatusCode.BadRequest, client.put("$base/key") { localJson("{broken $key}") }.status)
            assertFalse(client.get("$base/state").bodyAsText().contains(key))
            assertEquals(HttpStatusCode.Conflict, client.put("$base/settings") { localJson(apiJson.encodeToString(SettingsCommand(1, changed))) }.status)
        } finally { c.shutdown(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun `launch is immediate progress reconnect cancel and retry are durable`() = testApplication {
        engine { connector { host = "localhost"; port = 8080 } }
        val client = createClient { defaultRequest { if (!headers.contains(HttpHeaders.Host)) header(HttpHeaders.Host, "localhost:8080") } }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val c = controller(mode = ResponseMode.UNRESTRICTED) { model, _, _ ->
            count.incrementAndGet(); entered.complete(Unit); release.await(); completion(model)
        }
        application { workbenchModule(WorkbenchApi(c)) }
        try {
            val request = command()
            val response = withTimeout(2000) { client.post("$base/operations") { localJson(apiJson.encodeToString(request)) } }
            assertEquals(HttpStatusCode.Accepted, response.status)
            val id = apiJson.decodeFromString<StartReply>(response.bodyAsText()).operationId
            withTimeout(2000) { entered.await() }
            val current = client.get("$base/state").state()
            assertEquals(id, current.operation?.id)
            assertEquals(1, current.operation?.progress?.current)
            assertEquals(1, current.exchanges.size)
            // A fresh SSE subscriber gets the current snapshot, disconnecting it leaves the worker alive.
            withTimeout(4000) {
                client.prepareGet("$base/events").execute { events ->
                    assertEquals(ContentType.Text.EventStream, events.contentType()?.withoutParameters())
                    val channel = events.bodyAsChannel()
                    var snapshot: StateDto? = null
                    while (snapshot == null) {
                        val line = channel.readUTF8Line() ?: error("SSE closed")
                        if (line.startsWith("data:")) snapshot = apiJson.decodeFromString(line.removePrefix("data:").trim())
                    }
                    assertEquals(id, snapshot.operation?.id)
                    channel.cancel()
                }
            }
            assertTrue(c.state.value.isRunning)
            assertEquals(HttpStatusCode.Conflict, client.post("$base/operations") { localJson(apiJson.encodeToString(command())) }.status)
            assertEquals(HttpStatusCode.Conflict, client.delete("$base/history") { localJson() }.status)
            assertEquals(HttpStatusCode.Conflict, client.delete("$base/results") { localJson() }.status)
            assertEquals(HttpStatusCode.Conflict, client.put("$base/settings") { localJson(apiJson.encodeToString(SettingsCommand(0, current.settings))) }.status)
            assertEquals(HttpStatusCode.Conflict, client.put("$base/key") { localJson(apiJson.encodeToString(KeyCommand(0, "OPENAI", "other-key"))) }.status)
            val duplicate = client.post("$base/operations") { localJson(apiJson.encodeToString(request)) }
            assertEquals(id, apiJson.decodeFromString<StartReply>(duplicate.bodyAsText()).operationId)
            assertEquals(1, count.get())
            assertEquals(HttpStatusCode.OK, client.post("$base/operations/$id/cancel") { localJson() }.status)
            withTimeout(2000) { c.state.first { !it.isRunning } }
            assertEquals("cancelled", client.get("$base/state").state().exchanges.single().status)
            // A delayed retry after cancellation still cannot create another experiment.
            client.post("$base/operations") { localJson(apiJson.encodeToString(request)) }
            assertEquals(1, count.get())
            val next = client.post("$base/operations") { localJson(apiJson.encodeToString(command())) }
            val nextId = apiJson.decodeFromString<StartReply>(next.bodyAsText()).operationId
            assertNotEquals(id, nextId)
            client.post("$base/operations/$id/cancel") { localJson() }
            assertEquals(nextId, client.get("$base/state").state().operation?.id)
            release.complete(Unit)
            withTimeout(2000) { c.state.first { !it.isRunning } }
            assertEquals("completed", client.get("$base/state").state().exchanges.last().status)
        } finally { c.shutdown() }
    }

    @Test
    fun `all six modes produce complete DTOs preserve options and separate history`() = testApplication {
        engine { connector { host = "localhost"; port = 8080 } }
        val client = createClient { defaultRequest { if (!headers.contains(HttpHeaders.Host)) header(HttpHeaders.Host, "localhost:8080") } }
        data class Call(val messages: List<LlmMessage>, val options: CompletionOptions, val model: String)
        val calls = mutableListOf<Call>()
        val c = controller { model, messages, options -> calls += Call(messages, options, model); completion(model) }
        application { workbenchModule(WorkbenchApi(c)) }
        try {
            for ((mode, expected) in listOf("compare" to 2, "controlled" to 1, "unrestricted" to 1, "reasoning" to 6, "temperature" to 4, "models" to 4)) {
                val before = client.get("$base/state").state()
                val settings = before.settings.copy(mode = mode)
                val updated = client.put("$base/settings") { localJson(apiJson.encodeToString(SettingsCommand(before.settingsVersion, settings))) }.state()
                val start = calls.size
                client.post("$base/operations") { localJson(apiJson.encodeToString(command(updated.settingsVersion))) }
                withTimeout(4000) { c.state.first { !it.isRunning } }
                val result = client.get("$base/state").state()
                assertEquals(expected, calls.size - start, mode)
                assertEquals("completed", result.exchanges.last().status)
                assertEquals(expected, result.exchanges.last().outputs.size)
                if (mode in listOf("reasoning", "temperature", "models")) assertTrue(calls.drop(start).all { it.messages.size == 1 })
                if (mode == "compare") {
                    assertNull(calls[start].options.maxTokens)
                    assertEquals(300, calls[start + 1].options.maxTokens)
                    assertContains(calls[start + 1].messages.last().content, "не более 60 слов")
                    assertEquals(listOf(DEFAULT_STOP_SEQUENCE), calls[start + 1].options.stopSequences)
                }
                if (mode == "temperature") assertEquals(listOf(0.0, 0.7, 1.2, 0.0), calls.drop(start).map { it.options.temperature })
                if (mode == "models") {
                    assertEquals(1000, result.settings.maxTokens)
                    assertTrue(calls.drop(start).all { it.options.reasoningEffort == ReasoningEffort.MEDIUM && it.options.maxTokens == 1000 })
                    assertNotNull(result.exchanges.last().estimatedTotalCostUsd)
                }
            }
            val state = client.get("$base/state").state()
            assertEquals(HistoryDto(2, 2), state.history)
            val details = apiJson.decodeFromString<HistoryDetailsDto>(client.get("$base/history").bodyAsText())
            assertEquals(listOf("user", "assistant", "user", "assistant"), details.branches.getValue("controlled").map { it.role })
            assertContains(details.branches.getValue("controlled").first().content, "Требования к ответу")
            val cleared = client.delete("$base/results") { localJson() }.state()
            assertTrue(cleared.exchanges.isEmpty()); assertEquals(state.history, cleared.history)
            val history = client.delete("$base/history") { localJson() }.state()
            assertEquals(HistoryDto(0, 0), history.history)
            for (demo in listOf("reasoning", "temperature")) {
                client.post("$base/operations") { localJson(apiJson.encodeToString(command(history.settingsVersion, demo = demo))) }
                withTimeout(4000) { c.state.first { !it.isRunning } }
                assertEquals(demo, client.get("$base/state").state().exchanges.last().mode)
            }
        } finally { c.shutdown() }
    }

    @Test
    fun `completed stages survive failure and API output scrubs echoed old and new keys`() = testApplication {
        engine { connector { host = "localhost"; port = 8080 } }
        val client = createClient { defaultRequest { if (!headers.contains(HttpHeaders.Host)) header(HttpHeaders.Host, "localhost:8080") } }
        val count = AtomicInteger()
        val c = controller { _, _, _ ->
            if (count.incrementAndGet() == 2) throw IOException("test-secret-key")
            CompletionResult("test-secret-key in a provider response", "stop", null)
        }
        application { workbenchModule(WorkbenchApi(c)) }
        try {
            client.post("$base/operations") { localJson(apiJson.encodeToString(command())) }
            withTimeout(3000) { c.state.first { !it.isRunning } }
            val state = client.get("$base/state").state()
            assertEquals("failed", state.exchanges.single().status)
            assertEquals(1, state.exchanges.single().outputs.size)
            assertContains(state.exchanges.single().outputs.single().content!!, "••••")
            assertFalse(client.get("$base/state").bodyAsText().contains("test-secret-key"))
            client.put("$base/key") { localJson(apiJson.encodeToString(KeyCommand(0, "OPENAI", "new-secret-key"))) }
            assertFalse(client.get("$base/state").bodyAsText().contains("test-secret-key"))
        } finally { c.shutdown() }
    }

    @Test
    fun `partial model failures keep successes and evaluator metrics`() = testApplication {
        engine { connector { host = "localhost"; port = 8080 } }
        val client = createClient { defaultRequest { if (!headers.contains(HttpHeaders.Host)) header(HttpHeaders.Host, "localhost:8080") } }
        val c = controller(ResponseMode.MODEL_COMPARISON) { model, _, _ ->
            if (model.contains("terra")) throw LlmApiException("test-secret-key provider failure")
            completion(model)
        }
        application { workbenchModule(WorkbenchApi(c)) }
        try {
            client.post("$base/operations") { localJson(apiJson.encodeToString(command())) }
            withTimeout(3000) { c.state.first { !it.isRunning } }
            val e = client.get("$base/state").state().exchanges.single()
            assertEquals("completed", e.status)
            assertEquals(4, e.outputs.size)
            assertNotNull(e.outputs[1].error)
            assertNotNull(e.outputs.last().metrics.estimatedCostUsd)
            assertNotNull(e.estimatedTotalCostUsd)
            assertFalse(e.outputs[1].error!!.contains("test-secret-key"))
        } finally { c.shutdown() }
    }

    @Test
    fun `host origin csrf and malformed payloads are rejected without leaking credentials`() = testApplication {
        engine { connector { host = "localhost"; port = 8080 } }
        val client = createClient { defaultRequest { if (!headers.contains(HttpHeaders.Host)) header(HttpHeaders.Host, "localhost:8080") } }
        val c = controller()
        application { workbenchModule(WorkbenchApi(c)) }
        try {
            assertEquals(HttpStatusCode.Forbidden, client.get("$base/state") { header(HttpHeaders.Host, "evil.example:8080") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("$base/state") { header(HttpHeaders.Origin, "https://evil.example") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("$base/state") { header("Sec-Fetch-Site", "cross-site") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("$base/operations") { contentType(ContentType.Application.Json); setBody("{}") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("$base/operations") { contentType(ContentType.Application.FormUrlEncoded); setBody("prompt=x") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.options("$base/key") { header(HttpHeaders.Origin, "https://evil.example") }.status)
            val error = client.put("$base/key") { localJson("{\"key\":\"test-secret-key\",\"expectedSettingsVersion\":\"wrong\"}") }
            assertEquals(HttpStatusCode.BadRequest, error.status)
            assertFalse(error.bodyAsText().contains("test-secret-key"))
            assertNull(error.headers[HttpHeaders.AccessControlAllowOrigin])
        } finally { c.shutdown() }
    }

    @Test
    fun `persistence failures do not publish success or leak the entered key`() = testApplication {
        engine { connector { host = "localhost"; port = 8080 } }
        val client = createClient { defaultRequest { if (!headers.contains(HttpHeaders.Host)) header(HttpHeaders.Host, "localhost:8080") } }
        val c = controller(keys = emptyMap(), persist = { _, keys -> error("failed ${keys.values.joinToString()}") })
        application { workbenchModule(WorkbenchApi(c)) }
        try {
            val result = client.put("$base/key") { localJson(apiJson.encodeToString(KeyCommand(0, "OPENAI", "save-secret-key"))) }
            assertEquals(HttpStatusCode.InternalServerError, result.status)
            assertFalse(result.bodyAsText().contains("save-secret-key"))
            val state = client.get("$base/state").state()
            assertEquals(0, state.settingsVersion)
            assertFalse(state.providers.any { it.hasKey })
        } finally { c.shutdown() }
    }

    @Test
    fun `parallel launches and stale settings have one winner and mode rules are server owned`() = runBlocking {
        val c = controller { _, _, _ -> awaitCancellation() }
        val api = WorkbenchApi(c)
        try {
            val changed = api.settings(SettingsCommand(0, c.state.value.settings.toDto().copy(provider = "DEEPSEEK", mode = "models", maxTokens = 3)))
            assertEquals("OPENAI", changed.settings.provider)
            assertEquals(1000, changed.settings.maxTokens)
            val manual = api.settings(SettingsCommand(1, changed.settings.copy(maxTokens = 120)))
            assertEquals(120, manual.settings.maxTokens)
            val starts = (1..30).map { async(Dispatchers.Default) { runCatching { api.start(command(2)) } } }.awaitAll()
            assertEquals(1, starts.count { it.isSuccess })
            assertEquals(1, c.state.value.exchanges.size)
            assertTrue(starts.filter { it.isFailure }.all { (it.exceptionOrNull() as ApiProblem).status == 409 })
            c.cancelCurrent(); c.awaitCurrentRequest()
            val changes = (1..15).map { async(Dispatchers.Default) {
                runCatching { api.settings(SettingsCommand(2, manual.settings.copy(maxTokens = 100 + it))) }
            } }.awaitAll()
            assertEquals(1, changes.count { it.isSuccess })
        } finally { c.shutdown() }
    }

    @Test
    fun `provider model selections survive switching and stale launches cannot use new settings`() = runBlocking {
        val c = controller()
        val api = WorkbenchApi(c)
        try {
            val openai = api.settings(SettingsCommand(0, c.state.value.settings.toDto().copy(model = "gpt-4.1-mini")))
            val deepseek = api.settings(SettingsCommand(openai.settingsVersion, openai.settings.copy(provider = "DEEPSEEK")))
            assertEquals("deepseek-v4-flash", deepseek.settings.model)
            val returned = api.settings(SettingsCommand(deepseek.settingsVersion, deepseek.settings.copy(provider = "OPENAI")))
            assertEquals("gpt-4.1-mini", returned.settings.model)
            val stale = assertFailsWith<ApiProblem> { api.start(command(0)) }
            assertEquals("stale_settings", stale.code)
            assertTrue(c.state.value.exchanges.isEmpty())
            val modelMode = api.settings(SettingsCommand(returned.settingsVersion, returned.settings.copy(mode = "models")))
            val invalid = assertFailsWith<ApiProblem> { api.settings(SettingsCommand(modelMode.settingsVersion, modelMode.settings.copy(provider = "DEEPSEEK"))) }
            assertEquals(400, invalid.status)
        } finally { c.shutdown() }
    }

    companion object {
        fun completion(model: String) = CompletionResult("Ответ 👋", "stop", TokenUsage(20, 10, 30, reasoningTokens = 2), model)
    }
}
