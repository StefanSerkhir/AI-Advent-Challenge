package org.example.app

import org.example.agent.*
import org.example.llm.CompletionOptions
import org.example.llm.CompletionResult
import org.example.llm.LlmClient
import org.example.llm.LlmMessage

data class LabeledResponse(
    val variant: ResponseVariant,
    val completion: CompletionResult,
    val heading: String = variant.heading,
) {
    val content: String
        get() = completion.content
}

data class PromptProgress(
    val current: Int,
    val total: Int,
    val label: String,
)

class PromptRunner(
    private val onProgress: (PromptProgress) -> Unit = {},
    private val onDelta: (ExperimentOutputDelta) -> Unit = {},
    private val onResponse: (LabeledResponse) -> Unit = {},
    private val historyStore: ConversationHistoryStore = NoOpConversationHistoryStore,
    private val clientProvider: () -> LlmClient,
) {
    val historyLoadWarning: String?
    private val agents: Map<ResponseVariant, LlmAgent>

    init {
        val (restoredHistory, warning) = try {
            historyStore.load() to null
        } catch (_: Exception) {
            emptyMap<String, List<LlmMessage>>() to HISTORY_LOAD_WARNING
        }
        historyLoadWarning = warning
        agents = ResponseVariant.entries.associateWith { variant ->
            LlmAgent(
                clientProvider = clientProvider,
                initialHistory = restoredHistory[variant.historyId].orEmpty(),
                persistHistory = { completedHistory -> persist(variant, completedHistory) },
            )
        }
    }

    suspend fun complete(
        prompt: String,
        settings: AppSettings,
    ): List<LabeledResponse> {
        val variants = settings.responseMode.variants()
        return variants.mapIndexed { index, variant ->
            onProgress(
                PromptProgress(
                    current = index + 1,
                    total = variants.size,
                    label = when {
                        settings.responseMode == ResponseMode.UNRESTRICTED -> "Агент формирует ответ"
                        variant == ResponseVariant.UNRESTRICTED -> "Ответ без ограничений"
                        variant == ResponseVariant.CONTROLLED -> "Ответ с ограничениями"
                        else -> error("Неизвестный вариант ответа")
                    },
                ),
            )
            completeVariant(variant, prompt, settings).also(onResponse)
        }
    }

    /** Call from the owning worker, or while no request is running. */
    fun historySnapshot(): Map<ResponseVariant, List<LlmMessage>> = agents.mapValues { it.value.historySnapshot() }

    fun clearHistory() {
        historyStore.clear()
        agents.values.forEach(LlmAgent::clearHistory)
    }

    fun historyTurnCounts(): Map<ResponseVariant, Int> = agents.mapValues { it.value.completedTurnCount() }

    private fun persist(
        changedVariant: ResponseVariant,
        completedHistory: List<LlmMessage>,
    ) {
        val snapshot: ConversationHistorySnapshot = ResponseVariant.entries.associate { variant ->
            variant.historyId to if (variant == changedVariant) {
                completedHistory
            } else {
                agents.getValue(variant).historySnapshot()
            }
        }
        historyStore.save(snapshot)
    }

    private suspend fun completeVariant(
        variant: ResponseVariant,
        prompt: String,
        settings: AppSettings,
    ): LabeledResponse {
        val requestPrompt = when (variant) {
            ResponseVariant.UNRESTRICTED -> prompt
            ResponseVariant.CONTROLLED -> withResponseConstraints(prompt, settings)
        }
        val options = when (variant) {
            ResponseVariant.UNRESTRICTED -> CompletionOptions()
            ResponseVariant.CONTROLLED -> CompletionOptions(
                maxTokens = settings.maxTokens,
                stopSequences = settings.stopSequence?.let(::listOf).orEmpty(),
            )
        }
        val heading = if (settings.responseMode == ResponseMode.UNRESTRICTED) "ОТВЕТ АГЕНТА" else variant.heading
        val completion = agents.getValue(variant).respond(
            AgentRequest(
                prompt = requestPrompt,
                options = options,
                historyEnabled = settings.historyEnabled,
            ),
            onDelta = { content -> onDelta(ExperimentOutputDelta(variant.name, heading, content)) },
        ).completion

        return LabeledResponse(
            variant = variant,
            completion = completion,
            heading = heading,
        )
    }

    private fun ResponseMode.variants(): List<ResponseVariant> = when (this) {
        ResponseMode.COMPARE -> ResponseVariant.entries
        ResponseMode.CONTROLLED -> listOf(ResponseVariant.CONTROLLED)
        ResponseMode.UNRESTRICTED -> listOf(ResponseVariant.UNRESTRICTED)
        ResponseMode.REASONING -> error("Reasoning mode must be handled by ReasoningRunner")
        ResponseMode.TEMPERATURE -> error("Temperature mode must be handled by TemperatureRunner")
        ResponseMode.MODEL_COMPARISON -> error("Model comparison mode must be handled by ModelComparisonRunner")
    }
}

private val ResponseVariant.historyId: String
    get() = when (this) {
        ResponseVariant.UNRESTRICTED -> "unrestricted"
        ResponseVariant.CONTROLLED -> "controlled"
    }

private const val HISTORY_LOAD_WARNING =
    "Не удалось восстановить историю диалога: файл повреждён, недоступен или имеет неподдерживаемую версию. Начата пустая история."
