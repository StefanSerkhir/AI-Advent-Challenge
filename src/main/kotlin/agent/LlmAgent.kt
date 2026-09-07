package org.example.agent

import org.example.llm.*

/** A user request handled by an [Agent]. */
data class AgentRequest(
    val prompt: String,
    val options: CompletionOptions = CompletionOptions(),
    val historyEnabled: Boolean = true,
)

/** The agent response keeps the provider result, including model and token metadata. */
data class AgentResponse(
    val completion: CompletionResult,
) {
    val content: String
        get() = completion.content
}

/**
 * An application-level entity that turns a user request into an answer.
 *
 * The UI and use cases depend on this contract instead of invoking an LLM API client directly.
 */
interface Agent {
    suspend fun respond(request: AgentRequest): AgentResponse
}

/**
 * A simple conversational agent. It owns its dialogue memory, builds the API messages,
 * calls the configured LLM and records only successfully completed exchanges.
 *
 * One instance represents one independent conversation branch. It is expected to be used
 * by a single request worker at a time.
 */
class LlmAgent(
    private val clientProvider: () -> LlmClient,
) : Agent {
    private val history = mutableListOf<LlmMessage>()

    override suspend fun respond(request: AgentRequest): AgentResponse {
        val prompt = request.prompt.trim()
        require(prompt.isNotEmpty()) { "Запрос агенту не может быть пустым." }

        val userMessage = LlmMessage(LlmRole.USER, prompt)
        val messages = if (request.historyEnabled) history.toList() + userMessage else listOf(userMessage)
        val completion = clientProvider().complete(messages, request.options)

        if (request.historyEnabled) {
            history += userMessage
            history += LlmMessage(LlmRole.ASSISTANT, completion.content)
        }

        return AgentResponse(completion)
    }

    fun historySnapshot(): List<LlmMessage> = history.toList()

    fun completedTurnCount(): Int = history.count { it.role == LlmRole.ASSISTANT }

    fun clearHistory() = history.clear()
}
