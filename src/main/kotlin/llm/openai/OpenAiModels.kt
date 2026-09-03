package org.example.llm.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class ChatMessage(val role: String, val content: String? = null)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null,
)

@Serializable
data class ChatChoice(
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice>,
    val usage: ChatTokenUsage? = null,
    val model: String? = null,
)

@Serializable
data class ChatTokenUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)
