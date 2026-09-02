package org.example.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

const val LLM_REQUEST_TIMEOUT_MS = 120_000L
const val LLM_CONNECT_TIMEOUT_MS = 10_000L
const val LLM_SOCKET_TIMEOUT_MS = 120_000L
const val LLM_MAX_RETRIES = 2

fun createHttpClient(): HttpClient = HttpClient(CIO) {
    // Retry must be installed before HttpTimeout so Ktor can retry timeout exceptions.
    install(HttpRequestRetry) {
        retryIf(maxRetries = LLM_MAX_RETRIES) { _, response ->
            response.status.value in setOf(408, 409, 429) || response.status.value >= 500
        }
        retryOnException(maxRetries = LLM_MAX_RETRIES, retryOnTimeout = true)
        exponentialDelay()
    }
    install(HttpTimeout) {
        requestTimeoutMillis = LLM_REQUEST_TIMEOUT_MS
        connectTimeoutMillis = LLM_CONNECT_TIMEOUT_MS
        socketTimeoutMillis = LLM_SOCKET_TIMEOUT_MS
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
