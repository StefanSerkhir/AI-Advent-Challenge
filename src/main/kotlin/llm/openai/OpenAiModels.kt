package org.example.llm.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
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
    @SerialName("prompt_tokens_details")
    val promptTokensDetails: ChatPromptTokenDetails? = null,
    @SerialName("completion_tokens_details")
    val completionTokensDetails: ChatCompletionTokenDetails? = null,
)

@Serializable
data class ChatPromptTokenDetails(
    @SerialName("cached_tokens")
    val cachedTokens: Int = 0,
    @SerialName("cache_write_tokens")
    val cacheWriteTokens: Int = 0,
)

@Serializable
data class ChatCompletionTokenDetails(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int = 0,
)
