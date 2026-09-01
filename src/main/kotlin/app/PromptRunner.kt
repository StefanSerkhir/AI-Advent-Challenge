package org.example.app

import org.example.llm.CompletionOptions
import org.example.llm.LlmClient
import org.example.llm.LlmMessage
import org.example.llm.LlmRole

data class LabeledResponse(
    val variant: ResponseVariant,
    val content: String,
)

class PromptRunner(
    private val clientProvider: () -> LlmClient,
) {
    private val histories = ResponseVariant.entries.associateWith {
        mutableListOf<LlmMessage>()
    }

    suspend fun complete(
        prompt: String,
        settings: AppSettings,
    ): List<LabeledResponse> {
        val client = clientProvider()
        return settings.responseMode.variants().map { variant ->
            completeVariant(client, variant, prompt, settings)
        }
    }

    fun clearHistory() {
        histories.values.forEach(MutableList<LlmMessage>::clear)
    }

    fun historyTurnCounts(): Map<ResponseVariant, Int> = histories.mapValues { (_, messages) ->
        messages.count { it.role == LlmRole.ASSISTANT }
    }

    private suspend fun completeVariant(
        client: LlmClient,
        variant: ResponseVariant,
        prompt: String,
        settings: AppSettings,
    ): LabeledResponse {
        val requestPrompt = when (variant) {
            ResponseVariant.UNRESTRICTED -> prompt
            ResponseVariant.CONTROLLED -> withResponseConstraints(prompt, settings)
        }
        val userMessage = LlmMessage(LlmRole.USER, requestPrompt)
        val messages = if (settings.historyEnabled) {
            histories.getValue(variant).toList() + userMessage
        } else {
            listOf(userMessage)
        }
        val options = when (variant) {
            ResponseVariant.UNRESTRICTED -> CompletionOptions()
            ResponseVariant.CONTROLLED -> CompletionOptions(
                maxTokens = settings.maxTokens,
                stopSequences = settings.stopSequence?.let(::listOf).orEmpty(),
            )
        }
        val response = client.complete(messages, options)

        if (settings.historyEnabled) {
            histories.getValue(variant) += userMessage
            histories.getValue(variant) += LlmMessage(LlmRole.ASSISTANT, response)
        }

        return LabeledResponse(variant, response)
    }

    private fun ResponseMode.variants(): List<ResponseVariant> = when (this) {
        ResponseMode.COMPARE -> ResponseVariant.entries
        ResponseMode.CONTROLLED -> listOf(ResponseVariant.CONTROLLED)
        ResponseMode.UNRESTRICTED -> listOf(ResponseVariant.UNRESTRICTED)
    }
}
