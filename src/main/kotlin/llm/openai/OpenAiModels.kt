package org.example.llm.openai

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String? = null)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
)

@Serializable
data class ChatChoice(val message: ChatMessage)

@Serializable
data class ChatCompletionResponse(val choices: List<ChatChoice>)
