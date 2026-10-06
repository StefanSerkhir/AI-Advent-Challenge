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

fun createHttpClient(
    maxRetries: Int = LLM_MAX_RETRIES,
    requestTimeoutMillis: Long = LLM_REQUEST_TIMEOUT_MS,
    connectTimeoutMillis: Long = LLM_CONNECT_TIMEOUT_MS,
    socketTimeoutMillis: Long = LLM_SOCKET_TIMEOUT_MS,
): HttpClient {
    require(maxRetries >= 0) { "maxRetries must not be negative" }
    require(requestTimeoutMillis > 0 && connectTimeoutMillis > 0 && socketTimeoutMillis > 0) {
        "HTTP timeouts must be positive"
    }
    return HttpClient(CIO) {
        // Retry must be installed before HttpTimeout so Ktor can retry timeout exceptions.
        if (maxRetries > 0) {
            install(HttpRequestRetry) {
                retryIf(maxRetries = maxRetries) { _, response ->
                    response.status.value in setOf(408, 409, 429) || response.status.value >= 500
                }
                retryOnException(maxRetries = maxRetries, retryOnTimeout = true)
                exponentialDelay()
            }
        }
        install(HttpTimeout) {
            this.requestTimeoutMillis = requestTimeoutMillis
            this.connectTimeoutMillis = connectTimeoutMillis
            this.socketTimeoutMillis = socketTimeoutMillis
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
}
