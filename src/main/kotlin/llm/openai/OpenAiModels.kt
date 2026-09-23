package org.example.llm.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ChatTool(
    val type: String,
    val function: ChatFunctionDefinition,
)

@Serializable
data class ChatFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ChatToolCall(
    val id: String,
    val type: String,
    val function: ChatToolCallFunction,
)

@Serializable
data class ChatToolCallFunction(
    val name: String,
    val arguments: String,
)

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
    val stream: Boolean? = null,
    @SerialName("stream_options")
    val streamOptions: ChatCompletionStreamOptions? = null,
    @SerialName("response_format")
    val responseFormat: ChatCompletionResponseFormat? = null,
    val tools: List<ChatTool>? = null,
)

@Serializable
data class ChatCompletionResponseFormat(
    val type: String,
    @SerialName("json_schema")
    val jsonSchema: ChatCompletionJsonSchema,
)

@Serializable
data class ChatCompletionJsonSchema(
    val name: String,
    val strict: Boolean,
    val schema: JsonObject,
)

@Serializable
data class ChatCompletionStreamOptions(
    @SerialName("include_usage")
    val includeUsage: Boolean,
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
data class ChatCompletionChunk(
    val choices: List<ChatChunkChoice> = emptyList(),
    val usage: ChatTokenUsage? = null,
    val model: String? = null,
)

@Serializable
data class ChatChunkChoice(
    val delta: ChatMessageDelta = ChatMessageDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class ChatMessageDelta(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChatToolCallDelta> = emptyList(),
)

@Serializable
data class ChatToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: ChatToolCallFunctionDelta? = null,
)

@Serializable
data class ChatToolCallFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
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
