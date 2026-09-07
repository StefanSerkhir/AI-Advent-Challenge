package org.example.web

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

val apiJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

/** Exact authority allowlist protects against DNS rebinding. No wildcard CORS. */
data class LocalAccess(val port: Int = 8080, val devPort: Int? = null) {
    val hosts = setOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")
    val origins = hosts.map { "http://$it" }.toSet() +
        (devPort?.let { setOf("http://127.0.0.1:$it", "http://localhost:$it") } ?: emptySet())
}

fun Application.workbenchModule(api: WorkbenchApi, access: LocalAccess = LocalAccess()) {
    install(ContentNegotiation) { json(apiJson) }
    install(SSE)
    install(StatusPages) {
        exception<ApiProblem> { call, error -> call.respond(HttpStatusCode.fromValue(error.status), ErrorDto(error.code, error.message)) }
        exception<IllegalArgumentException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorDto("validation", "Проверьте параметры: положительные целые лимиты, непустые модель и stop sequence без переводов строки.")) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid_json", "Некорректный JSON или типы полей.")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid_json", "Некорректный JSON или типы полей.")) }
        exception<IOException> { call, _ -> call.respond(HttpStatusCode.InternalServerError, ErrorDto("persistence", "Не удалось сохранить .env. Проверьте права доступа к файлу.")) }
        exception<Throwable> { call, error ->
            if (error is CancellationException) throw error
            // Never echo request bodies, exception messages, or provider credentials.
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal", "Не удалось выполнить команду. Повторите попытку."))
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        val origin = call.request.header(HttpHeaders.Origin)
        val mutation = call.request.httpMethod !in listOf(HttpMethod.Get, HttpMethod.Head)
        if (call.request.header(HttpHeaders.Host) !in access.hosts ||
            (origin != null && origin !in access.origins) ||
            call.request.header("Sec-Fetch-Site") == "cross-site") {
            call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden", "Доступ разрешён только из локального приложения."))
            finish()
            return@intercept
        }
        if (mutation && (call.request.header("X-Workbench-Request") != "1" ||
                call.request.contentType().withoutParameters() != ContentType.Application.Json)) {
            call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden", "Используйте локальный JSON API приложения."))
            finish()
            return@intercept
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
    }
    routing {
        route("/api") {
            get("/state") { call.respondText(api.snapshot(), ContentType.Application.Json) }
            get("/settings") { call.respondText(api.snapshot(), ContentType.Application.Json) }
            put("/settings") { call.respondText(api.snapshot(api.settings(call.receive<SettingsCommand>())), ContentType.Application.Json) }
            put("/key") { call.respondText(api.snapshot(api.key(call.receive<KeyCommand>())), ContentType.Application.Json) }
            post("/operations") { call.respond(HttpStatusCode.Accepted, api.start(call.receive<StartCommand>())) }
            post("/operations/{id}/cancel") {
                val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiProblem(400, "validation", "Некорректный номер операции.")
                call.respondText(api.snapshot(api.cancel(id)), ContentType.Application.Json)
            }
            get("/history") { call.respondText(api.history(), ContentType.Application.Json) }
            delete("/history") { call.respondText(api.snapshot(api.clear(history = true)), ContentType.Application.Json) }
            delete("/results") { call.respondText(api.snapshot(api.clear(history = false)), ContentType.Application.Json) }
            delete("/notice") { api.controller.clearNotice(); call.respondText(api.snapshot(), ContentType.Application.Json) }
            sse("/events") {
                heartbeat { period = 15.seconds }
                // Every new connection immediately receives a complete snapshot. Slow clients may
                // skip intermediate snapshots; the latest one contains all completed stages.
                api.controller.state.collect { state ->
                    send(ServerSentEvent(data = api.snapshot(state.toDto()), event = "state", id = state.revision.toString(), retry = 1500))
                }
            }
        }
        staticResources("/", "web") { default("index.html") }
    }
}
