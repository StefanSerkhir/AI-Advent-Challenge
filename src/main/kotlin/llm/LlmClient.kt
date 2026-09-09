package org.example.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface LlmClient {
    suspend fun complete(
        messages: List<LlmMessage>,
        options: CompletionOptions = CompletionOptions(),
    ): CompletionResult

    suspend fun complete(
        prompt: String,
        options: CompletionOptions = CompletionOptions(),
    ): CompletionResult = complete(
        messages = listOf(LlmMessage(LlmRole.USER, prompt)),
        options = options,
    )

    /**
     * Streams a completion as it is produced by the provider.
     *
     * The default keeps simple adapters source-compatible. Production clients should override
     * this method so [TextDelta] values arrive before the complete response is available.
     */
    fun stream(
        messages: List<LlmMessage>,
        options: CompletionOptions = CompletionOptions(),
    ): Flow<CompletionEvent> = flow {
        val completion = complete(messages, options)
        if (completion.content.isNotEmpty()) emit(TextDelta(completion.content))
        emit(
            CompletionFinished(
                finishReason = completion.finishReason,
                usage = completion.usage,
                model = completion.model,
            ),
        )
    }

    fun stream(
        prompt: String,
        options: CompletionOptions = CompletionOptions(),
    ): Flow<CompletionEvent> = stream(
        messages = listOf(LlmMessage(LlmRole.USER, prompt)),
        options = options,
    )
}

sealed interface CompletionEvent

data class TextDelta(val text: String) : CompletionEvent

data class CompletionFinished(
    val finishReason: String?,
    val usage: TokenUsage?,
    val model: String? = null,
) : CompletionEvent

suspend fun LlmClient.streamToCompletion(
    messages: List<LlmMessage>,
    options: CompletionOptions = CompletionOptions(),
    onDelta: (accumulatedText: String) -> Unit = {},
): CompletionResult {
    val answer = StringBuilder()
    var finished: CompletionFinished? = null
    stream(messages, options).collect { event ->
        when (event) {
            is TextDelta -> {
                answer.append(event.text)
                onDelta(answer.toString())
            }
            is CompletionFinished -> finished = event
        }
    }
    val metadata = finished
        ?: throw LlmApiException("провайдер завершил поток без финального события")
    return CompletionResult(
        content = answer.toString(),
        finishReason = metadata.finishReason,
        usage = metadata.usage,
        model = metadata.model,
    )
}

suspend fun LlmClient.streamToCompletion(
    prompt: String,
    options: CompletionOptions = CompletionOptions(),
    onDelta: (accumulatedText: String) -> Unit = {},
): CompletionResult = streamToCompletion(
    messages = listOf(LlmMessage(LlmRole.USER, prompt)),
    options = options,
    onDelta = onDelta,
)

data class CompletionResult(
    val content: String,
    val finishReason: String?,
    val usage: TokenUsage?,
    val model: String? = null,
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val cachedPromptTokens: Int = 0,
    val cacheWritePromptTokens: Int = 0,
    val reasoningTokens: Int = 0,
)

enum class LlmRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

data class CompletionOptions(
    val maxTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val temperature: Double? = null,
    val reasoningEffort: ReasoningEffort? = null,
) {
    init {
        require(maxTokens == null || maxTokens > 0) {
            "maxTokens должен быть больше нуля"
        }
        require(stopSequences.none(String::isBlank)) {
            "stop sequence не может быть пустой"
        }
        require(temperature == null || temperature.isFinite() && temperature in 0.0..2.0) {
            "temperature должна быть числом от 0 до 2"
        }
    }
}

enum class ReasoningEffort(val apiValue: String) {
    MEDIUM("medium"),
}

open class LlmApiException(message: String) : RuntimeException(message)

class LlmContextApiException(
    message: String,
    val providerStatus: Int? = null,
) : LlmApiException(message)
