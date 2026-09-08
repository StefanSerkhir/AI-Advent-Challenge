package org.example.app

import io.ktor.client.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import org.example.agent.ConversationHistoryStore
import org.example.agent.HistoryPersistenceException
import org.example.agent.NoOpConversationHistoryStore
import org.example.llm.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class OperationProgress(
    val current: Int,
    val total: Int,
    val label: String,
) {
    val fraction: Float
        get() = if (total == 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
}

sealed interface OperationState {
    data object Idle : OperationState
    data class Running(val progress: OperationProgress) : OperationState
}

sealed interface RequestResult {
    data class Responses(val responses: List<LabeledResponse>) : RequestResult
    data class Reasoning(val report: ReasoningReport) : RequestResult
    data class Temperature(val report: TemperatureReport) : RequestResult
    data class ModelComparison(val report: ModelComparisonReport) : RequestResult
}

sealed interface ExchangeOutcome {
    data object Pending : ExchangeOutcome
    data class Completed(val result: RequestResult) : ExchangeOutcome
    data class Failed(val message: String) : ExchangeOutcome
    data object Cancelled : ExchangeOutcome
}

data class ConversationExchange(
    val id: Long,
    val prompt: String,
    val mode: ResponseMode,
    val outcome: ExchangeOutcome,
    val outputs: List<ExperimentOutput> = emptyList(),
)

enum class NoticeKind { INFO, ERROR }

data class UiNotice(val message: String, val kind: NoticeKind)

data class WorkbenchState(
    val revision: Long = 0,
    val settingsVersion: Long = 0,
    val settings: AppSettings,
    val configuredProviders: Set<LlmKind>,
    val historyTurnCounts: Map<ResponseVariant, Int>,
    val historyMessages: Map<ResponseVariant, List<LlmMessage>> = emptyMap(),
    val exchanges: List<ConversationExchange> = emptyList(),
    val operation: OperationState = OperationState.Idle,
    val notice: UiNotice? = null,
) {
    val isRunning: Boolean
        get() = operation is OperationState.Running
}

/** Commands and worker transitions share this monitor. History is worker-owned while running.
 * Persistence finishes before publishing settings, so failed writes never claim success. */
class WorkbenchController(
    initialSettings: AppSettings,
    initialApiKeys: Map<LlmKind, String>,
    initialWarning: String? = null,
    private val historyStore: ConversationHistoryStore = NoOpConversationHistoryStore,
    private val clientFactory: (LlmKind, String, String) -> LlmClient,
    private val persistSettings: (AppSettings, Map<LlmKind, String>) -> Unit = { _, _ -> },
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val apiKeys = initialApiKeys.filterValues { it.isNotBlank() }.toMutableMap()
    private var closed = false
    private val secrets = apiKeys.values.toMutableSet()
    private val modelSelections = LlmKind.entries.associateWith { kind ->
        if (kind == initialSettings.llmKind) initialSettings.model else LlmModels.defaultFor(kind)
    }.toMutableMap()
    private val requestClient = AtomicReference<LlmClient?>()
    private val nextExchangeId = AtomicLong(1)
    private val lastStreamPublishNanos = mutableMapOf<String, Long>()
    private val latestStreamingOutputs = mutableMapOf<String, ExperimentOutput>()
    private val promptRunner: PromptRunner = PromptRunner(
        onProgress = { reportProgress(it.current, it.total, it.label) },
        onDelta = { publishStreamingOutput(it.asOutput()) },
        onResponse = {
            addOutput(ExperimentOutput(it.variant.name, it.heading, it.completion))
            publish { it.copy(historyMessages = promptRunner.historySnapshot(), historyTurnCounts = promptRunner.historyTurnCounts()) }
        },
        historyStore = historyStore,
        clientProvider = { requestClient.get() ?: error("Клиент запроса не инициализирован") },
    )
    private val _state = MutableStateFlow(
        WorkbenchState(
            settings = initialSettings.copy(),
            configuredProviders = apiKeys.keys.toSet(),
            historyTurnCounts = promptRunner.historyTurnCounts(),
            historyMessages = promptRunner.historySnapshot(),
            notice = listOfNotNull(initialWarning, promptRunner.historyLoadWarning)
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString("\n")
                ?.let { UiNotice(it, NoticeKind.ERROR) },
        ),
    )
    val state: StateFlow<WorkbenchState> = _state.asStateFlow()

    @Volatile
    private var currentJob: Job? = null

    @Synchronized
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        if (_state.value.isRunning || closed) return
        val previous = _state.value.settings
        var next = transform(previous.copy())
        if (next.responseMode == ResponseMode.MODEL_COMPARISON) {
            next = next.copy(llmKind = LlmKind.OPENAI,
                maxTokens = if (previous.responseMode != next.responseMode) maxOf(next.maxTokens, 1000) else next.maxTokens)
        }
        if (next.llmKind != previous.llmKind) {
            next = next.copy(model = modelSelections.getValue(next.llmKind))
        }
        validateSettings(next)
        persistSettings(next.copy(), apiKeys.toMap())
        modelSelections[previous.llmKind] = previous.model
        modelSelections[next.llmKind] = next.model
        publish { it.copy(settings = next.copy(), settingsVersion = it.settingsVersion + 1, notice = null) }
    }

    fun updateModel(model: String) = updateSettings { it.copy(model = model) }

    @Synchronized
    fun saveApiKey(apiKey: String): Boolean {
        if (_state.value.isRunning || closed) return false
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty() && normalized.length <= 4096 && normalized.none { it.isISOControl() }) {
            "Введите непустой API-ключ без переводов строки (до 4096 символов)."
        }
        val settings = _state.value.settings
        val kind = if (settings.responseMode == ResponseMode.MODEL_COMPARISON) LlmKind.OPENAI else settings.llmKind
        val keys = apiKeys + (kind to normalized)
        // Do not include the entered key even when a persistence adapter echoes it in an exception.
        try { persistSettings(settings.copy(), keys) } catch (_: Exception) {
            throw IOException("Не удалось сохранить .env")
        }
        apiKeys[kind] = normalized
        secrets += normalized
        publish { it.copy(configuredProviders = apiKeys.keys.toSet(), settingsVersion = it.settingsVersion + 1,
            notice = UiNotice("API-ключ для ${kind.displayName()} сохранён в локальном .env.", NoticeKind.INFO)) }
        return true
    }

    @Synchronized
    fun clearNotice() { publish { it.copy(notice = null) } }

    @Synchronized
    fun clearHistory(): Boolean {
        if (_state.value.isRunning || closed) return false
        try {
            promptRunner.clearHistory()
            publish { it.copy(historyTurnCounts = promptRunner.historyTurnCounts(), historyMessages = promptRunner.historySnapshot(),
                notice = UiNotice("История обеих веток очищена.", NoticeKind.INFO)) }
            return true
        } catch (_: HistoryPersistenceException) {
            publish { it.copy(notice = UiNotice(HISTORY_SAVE_ERROR, NoticeKind.ERROR)) }
            return false
        }
    }

    @Synchronized
    fun clearResults() {
        if (_state.value.isRunning || closed) return
        publish { it.copy(exchanges = emptyList()) }
    }

    @Synchronized
    fun redact(value: String): String = secrets.filter(String::isNotEmpty)
        .sortedByDescending(String::length).fold(value) { safe, secret -> safe.replace(secret, "••••") }

    @Synchronized
    fun submit(prompt: String): Boolean = submitInternal(prompt = prompt, referenceAnswer = null)

    @Synchronized
    fun submitReasoningDemo(): Boolean = submitInternal(
        prompt = DEMO_REASONING_TASK.trimIndent(),
        referenceAnswer = DEMO_REASONING_REFERENCE.trimIndent(),
        forcedMode = ResponseMode.REASONING,
    )

    @Synchronized
    fun submitTemperatureDemo(): Boolean = submitInternal(
        prompt = DEMO_TEMPERATURE_PROMPT.trimIndent(),
        referenceAnswer = null,
        forcedMode = ResponseMode.TEMPERATURE,
    )

    @Synchronized
    fun cancelCurrent() {
        currentJob?.cancel(CancellationException("Отменено пользователем"))
    }

    suspend fun awaitCurrentRequest() {
        currentJob?.join()
    }

    @Synchronized
    override fun close() {
        closed = true
        currentJob?.cancel()
        workerScope.cancel()
    }

    suspend fun shutdown() {
        close()
        workerScope.coroutineContext[Job]?.join()
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun submitInternal(
        prompt: String,
        referenceAnswer: String?,
        forcedMode: ResponseMode? = null,
    ): Boolean {
        if (_state.value.isRunning || closed) return false
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isEmpty()) {
            publish { it.copy(notice = UiNotice("Введите запрос.", NoticeKind.ERROR)) }
            return false
        }
        val baseSettings = _state.value.settings
        val settings = baseSettings.copy(responseMode = forcedMode ?: baseSettings.responseMode)
        val requestKind = if (settings.responseMode == ResponseMode.MODEL_COMPARISON) {
            LlmKind.OPENAI
        } else {
            settings.llmKind
        }
        val apiKey = apiKeys[requestKind]
        if (apiKey == null) {
            publish {
                it.copy(
                    notice = UiNotice(
                        "API-ключ для ${requestKind.displayName()} не задан. Сохраните его в настройках.",
                        NoticeKind.ERROR,
                    ),
                )
            }
            return false
        }

        val exchangeId = nextExchangeId.getAndIncrement()
        val total = when (settings.responseMode) {
            ResponseMode.COMPARE -> 2
            ResponseMode.CONTROLLED, ResponseMode.UNRESTRICTED -> 1
            ResponseMode.REASONING -> TOTAL_REASONING_API_CALLS
            ResponseMode.TEMPERATURE -> TOTAL_TEMPERATURE_API_CALLS
            ResponseMode.MODEL_COMPARISON -> TOTAL_MODEL_COMPARISON_API_CALLS
        }
        val exchange = ConversationExchange(
            id = exchangeId,
            prompt = normalizedPrompt,
            mode = settings.responseMode,
            outcome = ExchangeOutcome.Pending,
        )
        publish {
            it.copy(
                exchanges = it.exchanges + exchange,
                operation = OperationState.Running(OperationProgress(0, total, "Подготовка запроса")),
                notice = null,
            )
        }

        currentJob = workerScope.launch(start = CoroutineStart.ATOMIC) {
            try {
                ensureActive()
                if (settings.responseMode != ResponseMode.MODEL_COMPARISON) {
                    requestClient.set(clientFactory(requestKind, apiKey, settings.model))
                }
                val result = when (settings.responseMode) {
                    ResponseMode.COMPARE, ResponseMode.CONTROLLED, ResponseMode.UNRESTRICTED ->
                        RequestResult.Responses(promptRunner.complete(normalizedPrompt, settings))

                    ResponseMode.REASONING -> RequestResult.Reasoning(
                        ReasoningRunner(
                            onDelta = { publishStreamingOutput(it.asOutput()) },
                            onSolution = { addOutput(ExperimentOutput(it.variant.name, it.variant.heading, it.completion)) },
                            onGeneratedPrompt = { addOutput(ExperimentOutput("prompt", "Сгенерированный промпт", it, kind = "prompt")) },
                            onProgress = { reportProgress(it.current, it.total, it.label) },
                            clientProvider = { requestClient.get() ?: error("Клиент запроса не инициализирован") },
                        ).compare(normalizedPrompt, referenceAnswer),
                    )

                    ResponseMode.TEMPERATURE -> RequestResult.Temperature(
                        TemperatureRunner(
                            onDelta = { publishStreamingOutput(it.asOutput()) },
                            onSample = { addOutput(ExperimentOutput("t${it.temperature}", "Temperature = ${it.temperature.label()}", it.completion)) },
                            onProgress = { reportProgress(it.current, it.total, it.label) },
                            clientProvider = { requestClient.get() ?: error("Клиент запроса не инициализирован") },
                        ).compare(normalizedPrompt),
                    )

                    ResponseMode.MODEL_COMPARISON -> RequestResult.ModelComparison(
                        ModelComparisonRunner(
                            onDelta = { publishStreamingOutput(it.asOutput()) },
                            onRun = { addOutput(it.asOutput()) },
                            onProgress = { reportProgress(it.current, it.total, it.label) },
                            clientProvider = { model -> clientFactory(LlmKind.OPENAI, apiKey, model) },
                            errorMessage = { userFacingError(it, apiKeys.values) },
                        ).compare(normalizedPrompt, settings.maxTokens),
                    )
                }
                when (result) {
                    is RequestResult.Reasoning -> addOutput(ExperimentOutput("evaluation", "Сравнение и оценка точности", result.report.evaluation, kind = "evaluation"))
                    is RequestResult.Temperature -> addOutput(ExperimentOutput("evaluation", "Выводы по использованию", result.report.evaluation, kind = "evaluation"))
                    is RequestResult.ModelComparison -> result.report.evaluation?.let { addOutput(it.asOutput(evaluation = true)) }
                    else -> Unit
                }
                updateExchange(exchangeId, ExchangeOutcome.Completed(result))
            } catch (_: CancellationException) {
                updateExchange(exchangeId, ExchangeOutcome.Cancelled)
            } catch (error: Exception) {
                val message = userFacingError(error, apiKeys.values)
                updateExchange(exchangeId, ExchangeOutcome.Failed(message))
                publish { it.copy(notice = UiNotice(message, NoticeKind.ERROR)) }
            } finally {
                synchronized(this@WorkbenchController) {
                    requestClient.set(null)
                    currentJob = null
                    publish { it.copy(operation = OperationState.Idle, historyTurnCounts = promptRunner.historyTurnCounts()) }
                }
            }
        }
        return true
    }

    private fun reportProgress(current: Int, total: Int, label: String) {
        publish {
            if (it.operation is OperationState.Running) {
                it.copy(operation = OperationState.Running(OperationProgress(current, total, label)))
            } else {
                it
            }
        }
    }

    @Synchronized
    private fun updateExchange(id: Long, outcome: ExchangeOutcome) {
        val latestOutputs = latestStreamingOutputs.toMap()
        lastStreamPublishNanos.clear()
        latestStreamingOutputs.clear()
        publish { current ->
            current.copy(
                exchanges = current.exchanges.map { exchange ->
                    if (exchange.id == id) {
                        exchange.copy(
                            outcome = outcome,
                            outputs = exchange.outputs.map { output ->
                                (latestOutputs[output.id] ?: output).copy(streaming = false)
                            },
                        )
                    } else {
                        exchange
                    }
                },
            )
        }
    }

    @Synchronized
    private fun addOutput(output: ExperimentOutput) {
        lastStreamPublishNanos.remove(output.id)
        latestStreamingOutputs.remove(output.id)
        publish { current -> current.copy(exchanges = current.exchanges.map { exchange ->
            if (exchange.outcome == ExchangeOutcome.Pending) {
                val index = exchange.outputs.indexOfFirst { it.id == output.id }
                exchange.copy(
                    outputs = if (index < 0) {
                        exchange.outputs + output
                    } else {
                        exchange.outputs.toMutableList().also { it[index] = output }
                    },
                )
            } else {
                exchange
            }
        }) }
    }

    @Synchronized
    private fun publishStreamingOutput(output: ExperimentOutput) {
        latestStreamingOutputs[output.id] = output
        val now = System.nanoTime()
        val previous = lastStreamPublishNanos[output.id]
        if (previous != null && now - previous < STREAM_PUBLISH_INTERVAL_NANOS) return
        lastStreamPublishNanos[output.id] = now
        publish { current -> current.copy(exchanges = current.exchanges.map { exchange ->
            if (exchange.outcome == ExchangeOutcome.Pending) {
                val index = exchange.outputs.indexOfFirst { it.id == output.id }
                exchange.copy(
                    outputs = if (index < 0) {
                        exchange.outputs + output
                    } else {
                        exchange.outputs.toMutableList().also { it[index] = output }
                    },
                )
            } else {
                exchange
            }
        }) }
    }

    @Synchronized
    private fun publish(transform: (WorkbenchState) -> WorkbenchState) {
        _state.update { transform(it).copy(revision = it.revision + 1) }
    }
}

private const val STREAM_PUBLISH_INTERVAL_NANOS = 50_000_000L

internal fun userFacingError(error: Throwable, secrets: Collection<String> = emptyList()): String {
    val message = when (error) {
        is HistoryPersistenceException -> HISTORY_SAVE_ERROR
        is LlmApiException -> error.message ?: "Провайдер вернул ошибку."
        is HttpRequestTimeoutException -> "Превышено время ожидания ответа. Попробуйте ещё раз."
        is SerializationException -> "Провайдер вернул ответ в неожиданном формате. Попробуйте ещё раз."
        is IOException -> "Ошибка сети. Проверьте подключение к интернету и повторите запрос."
        is IllegalArgumentException -> error.message ?: "Проверьте параметры запроса."
        else -> "Не удалось выполнить запрос: ${error.message ?: error::class.simpleName ?: "неизвестная ошибка"}"
    }
    return secrets.filter(String::isNotEmpty).sortedByDescending(String::length).fold(message) { safe, secret ->
        safe.replace(secret, "••••")
    }
}

private const val HISTORY_SAVE_ERROR =
    "Не удалось сохранить историю диалога. Новые сообщения не добавлены; проверьте права доступа к файлу."

fun LlmKind.displayName(): String = when (this) {
    LlmKind.DEEPSEEK -> "DeepSeek"
    LlmKind.OPENAI -> "OpenAI"
}
