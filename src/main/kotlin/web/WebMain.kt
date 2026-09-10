package org.example.web

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import org.example.agent.JsonContextStateStore
import org.example.agent.JsonConversationHistoryStore
import org.example.app.AppBootstrap
import org.example.app.WorkbenchController
import org.example.config.LocalConfig
import org.example.config.LocalConfigStore
import org.example.llm.createLlmClient
import org.example.network.createHttpClient

fun main() {
    val port = configuredPort("WEB_PORT", 8080)
    val devPort = System.getenv("WEB_DEV_PORT")?.let { configuredPort("WEB_DEV_PORT", 5173) }
    val store = LocalConfigStore()
    val loaded = runCatching(store::load)
    val bootstrap = AppBootstrap.from(loaded.getOrDefault(LocalConfig()))
    val client = createHttpClient()
    val controller = WorkbenchController(bootstrap.settings, bootstrap.apiKeys,
        initialWarning = if (loaded.isFailure) "Не удалось прочитать .env; используются настройки по умолчанию." else bootstrap.warning,
        historyStore = JsonConversationHistoryStore(),
        contextStateStore = JsonContextStateStore(),
        clientFactory = { kind, key, model -> createLlmClient(kind, key, client, model) },
        persistSettings = store::save)
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) { workbenchModule(WorkbenchApi(controller), LocalAccess(port, devPort)) }
    val shutdown = Thread {
        runBlocking { controller.shutdown() }
        client.close()
        server.stop(500, 2000)
    }
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        server.start(wait = false)
        println("LLM Workbench → http://127.0.0.1:$port")
        Thread.currentThread().join()
    } finally {
        runBlocking { controller.shutdown() }
        client.close()
        server.stop(500, 2000)
    }
}

private fun configuredPort(name: String, default: Int): Int {
    val raw = System.getenv(name) ?: return default
    return raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("$name должен быть числом от 1 до 65535.")
}
