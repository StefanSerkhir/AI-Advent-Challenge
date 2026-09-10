package org.example.agent

import org.example.llm.*

data class ContextAgentResponse(
    val completion: CompletionResult,
    val sentMessages: List<LlmMessage>,
    val contextState: ContextSessionState,
    val summarizationError: String? = null,
)

/** Conversational agent whose application-owned memory is supplied by [ContextManager]. */
class ContextManagingAgent(
    private val sessionId: String,
    private val contextManager: ContextManager,
    private val clientProvider: () -> LlmClient,
) {
    suspend fun respond(
        prompt: String,
        options: CompletionOptions = CompletionOptions(),
    ): ContextAgentResponse {
        val text = prompt.trim()
        require(text.isNotEmpty()) { "Prompt must not be blank" }
        val user = LlmMessage(LlmRole.USER, text)
        val context = contextManager.contextFor(sessionId, user)
        val completion = clientProvider().complete(context, options)
        contextManager.recordMainUsage(sessionId, completion.usage)
        val summaryFailure = runCatching {
            contextManager.addExchange(sessionId, user, LlmMessage(LlmRole.ASSISTANT, completion.content))
        }.exceptionOrNull()
        return ContextAgentResponse(
            completion = completion,
            sentMessages = context,
            contextState = contextManager.state(sessionId),
            summarizationError = summaryFailure?.message,
        )
    }
}
