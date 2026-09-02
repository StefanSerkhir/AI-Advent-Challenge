package org.example.network

import io.ktor.client.plugins.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HttpClientFactoryTest {

    @Test
    fun `client has explicit LLM timeouts and retry plugins`() {
        val client = createHttpClient()

        try {
            assertNotNull(client.pluginOrNull(HttpRequestRetry))
            assertNotNull(client.pluginOrNull(HttpTimeout))
            assertEquals(120_000L, LLM_REQUEST_TIMEOUT_MS)
            assertEquals(10_000L, LLM_CONNECT_TIMEOUT_MS)
            assertEquals(120_000L, LLM_SOCKET_TIMEOUT_MS)
            assertEquals(2, LLM_MAX_RETRIES)
        } finally {
            client.close()
        }
    }
}
