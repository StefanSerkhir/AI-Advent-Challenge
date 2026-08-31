package org.example

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Message(val role: String, val content: String? = null)

@Serializable
data class ChatRequest(
    val model: String = "deepseek-v4-flash",
    val messages: List<Message>,
)

@Serializable
data class Choice(val message: Message)

@Serializable
data class ChatResponse(val choices: List<Choice>)

fun main(args: Array<String>) = runBlocking {
    val apiKey = System.getenv("DEEPSEEK_API_KEY")
        ?: error("Set the DEEPSEEK_API_KEY environment variable")
    val prompt = args.joinToString(" ").ifBlank { "Привет! Расскажи короткую шутку." }

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    try {
        val httpResponse = client.post("https://api.deepseek.com/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(messages = listOf(Message("user", prompt))))
        }

        if (!httpResponse.status.isSuccess()) {
            error("DeepSeek API error ${httpResponse.status}: ${httpResponse.bodyAsText()}")
        }

        val answer = httpResponse.body<ChatResponse>()
            .choices.firstOrNull()?.message?.content
            ?: error("DeepSeek returned an empty response")
        println(answer)
    } finally {
        client.close()
    }
}
