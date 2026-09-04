package org.example.app

import io.ktor.client.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import org.example.llm.LlmApiException
import org.example.llm.LlmClient
import org.example.llm.LlmKind
import org.example.llm.LlmModels
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
)

enum class NoticeKind { INFO, ERROR }

data class UiNotice(val message: String, val kind: NoticeKind)

data class DesktopUiState(
    val settings: AppSettings,
    val configuredProviders: Set<LlmKind>,
    val historyTurnCounts: Map<ResponseVariant, Int>,
    val exchanges: List<ConversationExchange> = emptyList(),
    val operation: OperationState = OperationState.Idle,
    val notice: UiNotice? = null,
) {
    val isRunning: Boolean
        get() = operation is OperationState.Running
}

class DesktopAppController(
    initialSettings: AppSettings,
    initialApiKeys: Map<LlmKind, String>,
    initialWarning: String? = null,
    private val clientFactory: (LlmKind, String, String) -> LlmClient,
    private val persistSettings: (AppSettings, Map<LlmKind, String>) -> Unit = { _, _ -> },
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private val apiKeys = initialApiKeys.toMutableMap()
    private val modelSelections = LlmKind.entries.associateWith { kind ->
        if (kind == initialSettings.llmKind) initialSettings.model else LlmModels.defaultFor(kind)
    }.toMutableMap()
    private val requestClient = AtomicReference<LlmClient?>()
    private val nextExchangeId = AtomicLong(1)
    private val promptRunner = PromptRunner(
        onProgress = { reportProgress(it.current, it.total, it.label) },
        clientProvider = { requestClient.get() ?: error("Клиент запроса не инициализирован") },
    )
    private val _state = MutableStateFlow(
        DesktopUiState(
            settings = initialSettings.copy(),
            configuredProviders = apiKeys.keys.toSet(),
            historyTurnCounts = promptRunner.historyTurnCounts(),
            notice = initialWarning?.let { UiNotice(it, NoticeKind.ERROR) },
        ),
    )
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    @Volatile
    private var currentJob: Job? = null

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        if (_state.value.isRunning) return
        val previous = _state.value.settings
        val transformed = transform(previous.copy())
        val next = if (transformed.llmKind != previous.llmKind) {
            modelSelections[previous.llmKind] = previous.model
            transformed.copy(model = modelSelections.getValue(transformed.llmKind))
        } else {
            transformed
        }
        modelSelections[next.llmKind] = next.model
        _state.update { it.copy(settings = next.copy(), notice = null) }
        persistAsync()
    }

    fun updateModel(model: String) {
        if (model.isBlank()) return
        updateSettings { it.copy(model = model) }
    }

    fun saveApiKey(apiKey: String): Boolean {
        if (_state.value.isRunning) return false
        val normalized = apiKey.trim()
        if (normalized.isEmpty()) {
            _state.update {
                it.copy(notice = UiNotice("Введите API-ключ перед сохранением.", NoticeKind.ERROR))
            }
            return false
        }
        val settings = _state.value.settings
        val kind = if (settings.responseMode == ResponseMode.MODEL_COMPARISON) {
            LlmKind.OPENAI
        } else {
            settings.llmKind
        }
        apiKeys[kind] = normalized
        _state.update {
            it.copy(
                configuredProviders = apiKeys.keys.toSet(),
                notice = UiNotice("API-ключ для ${kind.displayName()} сохранён в локальном .env.", NoticeKind.INFO),
            )
        }
        persistAsync()
        return true
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearHistory() {
        if (_state.value.isRunning) return
        promptRunner.clearHistory()
        _state.update {
            it.copy(
                historyTurnCounts = promptRunner.historyTurnCounts(),
                notice = UiNotice("История обеих веток очищена.", NoticeKind.INFO),
            )
        }
    }

    fun clearResults() {
        if (_state.value.isRunning) return
        _state.update { it.copy(exchanges = emptyList()) }
    }

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

    fun cancelCurrent() {
        currentJob?.cancel(CancellationException("Отменено пользователем"))
    }

    suspend fun awaitCurrentRequest() {
        currentJob?.join()
    }

    override fun close() {
        currentJob?.cancel()
        runCatching { persistSettings(_state.value.settings.copy(), apiKeys.toMap()) }
        workerScope.cancel()
    }

    private fun submitInternal(
        prompt: String,
        referenceAnswer: String?,
        forcedMode: ResponseMode? = null,
    ): Boolean {
        if (_state.value.isRunning) return false
        val normalizedPrompt = prompt.trim()
        if (normalizedPrompt.isEmpty()) {
            _state.update { it.copy(notice = UiNotice("Введите запрос.", NoticeKind.ERROR)) }
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
            _state.update {
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
        _state.update {
            it.copy(
                exchanges = it.exchanges + exchange,
                operation = OperationState.Running(OperationProgress(0, total, "Подготовка запроса")),
                notice = null,
            )
        }

        currentJob = workerScope.launch {
            try {
                if (settings.responseMode != ResponseMode.MODEL_COMPARISON) {
                    requestClient.set(clientFactory(requestKind, apiKey, settings.model))
                }
                val result = when (settings.responseMode) {
                    ResponseMode.COMPARE, ResponseMode.CONTROLLED, ResponseMode.UNRESTRICTED ->
                        RequestResult.Responses(promptRunner.complete(normalizedPrompt, settings))

                    ResponseMode.REASONING -> RequestResult.Reasoning(
                        ReasoningRunner(
                            onProgress = { reportProgress(it.current, it.total, it.label) },
                            clientProvider = { requestClient.get() ?: error("Клиент запроса не инициализирован") },
                        ).compare(normalizedPrompt, referenceAnswer),
                    )

                    ResponseMode.TEMPERATURE -> RequestResult.Temperature(
                        TemperatureRunner(
                            onProgress = { reportProgress(it.current, it.total, it.label) },
                            clientProvider = { requestClient.get() ?: error("Клиент запроса не инициализирован") },
                        ).compare(normalizedPrompt),
                    )

                    ResponseMode.MODEL_COMPARISON -> RequestResult.ModelComparison(
                        ModelComparisonRunner(
                            onProgress = { reportProgress(it.current, it.total, it.label) },
                            clientProvider = { model -> clientFactory(LlmKind.OPENAI, apiKey, model) },
                            errorMessage = { userFacingError(it, apiKeys.values) },
                        ).compare(normalizedPrompt, settings.maxTokens),
                    )
                }
                updateExchange(exchangeId, ExchangeOutcome.Completed(result))
            } catch (_: CancellationException) {
                updateExchange(exchangeId, ExchangeOutcome.Cancelled)
            } catch (error: Exception) {
                val message = userFacingError(error, apiKeys.values)
                updateExchange(exchangeId, ExchangeOutcome.Failed(message))
                _state.update { it.copy(notice = UiNotice(message, NoticeKind.ERROR)) }
            } finally {
                requestClient.set(null)
                _state.update {
                    it.copy(
                        operation = OperationState.Idle,
                        historyTurnCounts = promptRunner.historyTurnCounts(),
                    )
                }
            }
        }
        return true
    }

    private fun reportProgress(current: Int, total: Int, label: String) {
        _state.update {
            if (it.operation is OperationState.Running) {
                it.copy(operation = OperationState.Running(OperationProgress(current, total, label)))
            } else {
                it
            }
        }
    }

    private fun updateExchange(id: Long, outcome: ExchangeOutcome) {
        _state.update { current ->
            current.copy(
                exchanges = current.exchanges.map { exchange ->
                    if (exchange.id == id) exchange.copy(outcome = outcome) else exchange
                },
            )
        }
    }

    private fun persistAsync() {
        val settings = _state.value.settings.copy()
        val keys = apiKeys.toMap()
        workerScope.launch {
            runCatching { persistSettings(settings, keys) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            notice = UiNotice(
                                "Не удалось сохранить настройки: ${userFacingError(error, apiKeys.values)}",
                                NoticeKind.ERROR,
                            ),
                        )
                    }
                }
        }
    }
}

internal fun userFacingError(error: Throwable, secrets: Collection<String> = emptyList()): String {
    val message = when (error) {
        is LlmApiException -> error.message ?: "Провайдер вернул ошибку."
        is HttpRequestTimeoutException -> "Превышено время ожидания ответа. Попробуйте ещё раз."
        is SerializationException -> "Провайдер вернул ответ в неожиданном формате. Попробуйте ещё раз."
        is IOException -> "Ошибка сети. Проверьте подключение к интернету и повторите запрос."
        is IllegalArgumentException -> error.message ?: "Проверьте параметры запроса."
        else -> "Не удалось выполнить запрос: ${error.message ?: error::class.simpleName ?: "неизвестная ошибка"}"
    }
    return secrets.filter { it.length >= 4 }.fold(message) { safe, secret ->
        safe.replace(secret, "••••")
    }
}

fun LlmKind.displayName(): String = when (this) {
    LlmKind.DEEPSEEK -> "DeepSeek"
    LlmKind.OPENAI -> "OpenAI"
}
