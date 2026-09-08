package org.example.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.app.*
import org.example.llm.LlmKind
import org.example.llm.LlmModel
import org.example.llm.LlmModels

private val Background = Color(0xFFF5F6FA)
private val SidebarBackground = Color(0xFFFAFBFD)
private val Primary = Color(0xFF4157D8)
private val PrimaryDark = Color(0xFF293BAF)
private val Success = Color(0xFF17865D)
private val Danger = Color(0xFFB3261E)
private val Muted = Color(0xFF667085)

@Composable
fun DesktopApp(controller: DesktopAppController) {
    val state by controller.state.collectAsState()
    var prompt by remember { mutableStateOf("") }

    MaterialTheme(
        colors = lightColors(
            primary = Primary,
            primaryVariant = PrimaryDark,
            secondary = Success,
            background = Background,
            surface = Color.White,
            error = Danger,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            Column {
                AppHeader(state.settings, state.isRunning)
                Divider()
                Row(Modifier.fillMaxSize()) {
                    SettingsPanel(
                        state = state,
                        enabled = !state.isRunning,
                        onUpdateSettings = controller::updateSettings,
                        onUpdateModel = controller::updateModel,
                        onSaveApiKey = controller::saveApiKey,
                        onClearHistory = { controller.clearHistory() },
                        onReasoningDemo = controller::submitReasoningDemo,
                        onTemperatureDemo = controller::submitTemperatureDemo,
                    )
                    Divider(Modifier.fillMaxHeight().width(1.dp))
                    ConversationPanel(
                        modifier = Modifier.weight(1f),
                        exchanges = state.exchanges,
                        operation = state.operation,
                        notice = state.notice,
                        prompt = prompt,
                        onPromptChange = { prompt = it },
                        onSend = {
                            if (controller.submit(prompt)) prompt = ""
                        },
                        onCancel = controller::cancelCurrent,
                        onDismissNotice = controller::clearNotice,
                        onClearResults = controller::clearResults,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader(settings: AppSettings, isRunning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("LLM Workbench", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Диалоги и сравнение стратегий", color = Muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(9.dp).background(if (isRunning) Color(0xFFF5A524) else Success, RoundedCornerShape(50)),
            )
            Text(
                if (isRunning) "Выполняется запрос" else "Готово",
                color = Muted,
                fontSize = 13.sp,
            )
            Text(
                if (settings.responseMode == ResponseMode.MODEL_COMPARISON) {
                    "OpenAI · Luna / Terra / Sol"
                } else {
                    "${settings.llmKind.displayName()} · ${settings.model}"
                },
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    state: org.example.app.DesktopUiState,
    enabled: Boolean,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateModel: (String) -> Unit,
    onSaveApiKey: (String) -> Boolean,
    onClearHistory: () -> Unit,
    onReasoningDemo: () -> Unit,
    onTemperatureDemo: () -> Unit,
) {
    val settings = state.settings
    var apiKeyDraft by remember(settings.llmKind) { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val connectionSelectionEnabled = enabled && settings.responseMode != ResponseMode.MODEL_COMPARISON

    Column(
        Modifier.width(320.dp).fillMaxHeight().background(SidebarBackground)
            .verticalScroll(scrollState).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle("Подключение")
        DropdownSelector(
            label = "Провайдер",
            selected = settings.llmKind.displayName(),
            options = LlmKind.entries.map { it to it.displayName() },
            enabled = connectionSelectionEnabled,
            onSelected = { kind -> onUpdateSettings { it.copy(llmKind = kind) } },
        )
        val configuredModel = LlmModel(settings.model, settings.model)
        val models = (LlmModels.availableFor(settings.llmKind) + configuredModel).distinctBy(LlmModel::id)
        DropdownSelector(
            label = "Модель",
            selected = models.first { it.id == settings.model }.displayName,
            options = models.map { it.id to it.displayName },
            enabled = connectionSelectionEnabled,
            onSelected = onUpdateModel,
        )
        OutlinedTextField(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("API Key") },
            placeholder = {
                Text(
                    if (settings.llmKind in state.configuredProviders) "Ключ уже сохранён" else "Введите ключ",
                    fontSize = 12.sp,
                )
            },
        )
        Button(
            onClick = {
                if (onSaveApiKey(apiKeyDraft)) apiKeyDraft = ""
            },
            enabled = enabled && apiKeyDraft.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (settings.llmKind in state.configuredProviders) "Заменить ключ" else "Сохранить ключ")
        }
        Text(
            if (settings.llmKind in state.configuredProviders) "Ключ настроен · значение скрыто" else "Ключ не настроен",
            color = if (settings.llmKind in state.configuredProviders) Success else Danger,
            fontSize = 12.sp,
        )

        Divider()
        SectionTitle("Режим ответа")
        DropdownSelector(
            label = "Режим",
            selected = settings.responseMode.title(),
            options = ResponseMode.entries.map { it to it.title() },
            enabled = enabled,
            onSelected = { mode ->
                onUpdateSettings {
                    if (mode == ResponseMode.MODEL_COMPARISON) {
                        it.copy(
                            responseMode = mode,
                            llmKind = LlmKind.OPENAI,
                            maxTokens = maxOf(it.maxTokens, 1_000),
                        )
                    } else {
                        it.copy(responseMode = mode)
                    }
                }
            },
        )
        if (settings.responseMode == ResponseMode.MODEL_COMPARISON) {
            Text(
                "Один промпт будет последовательно отправлен GPT-5.6 Luna, Terra и Sol. " +
                    "Нужен ключ OpenAI; выбранная одиночная модель не используется.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        NumericSetting("Максимум токенов", settings.maxTokens, enabled) { value ->
            onUpdateSettings { it.copy(maxTokens = value) }
        }
        if (settings.responseMode != ResponseMode.MODEL_COMPARISON) {
            NumericSetting("Максимум слов", settings.maxWords, enabled) { value ->
                onUpdateSettings { it.copy(maxWords = value) }
            }
            NumericSetting("Пунктов в списке", settings.bulletCount, enabled) { value ->
                onUpdateSettings { it.copy(bulletCount = value) }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("История диалога", fontWeight = FontWeight.Medium)
                    val turns = state.historyTurnCounts.values.sum()
                    Text("Сохранено ходов: $turns", color = Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.historyEnabled,
                    onCheckedChange = { enabledValue ->
                        onUpdateSettings { it.copy(historyEnabled = enabledValue) }
                    },
                    enabled = enabled,
                )
            }
            OutlinedButton(onClick = onClearHistory, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Очистить историю")
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.stopSequence != null,
                    onCheckedChange = { checked ->
                        onUpdateSettings {
                            it.copy(stopSequence = if (checked) org.example.app.DEFAULT_STOP_SEQUENCE else null)
                        }
                    },
                    enabled = enabled,
                )
                Text("Использовать stop sequence", fontSize = 13.sp)
            }
            if (settings.stopSequence != null) {
                StopSequenceSetting(settings.stopSequence.orEmpty(), enabled) { value ->
                    onUpdateSettings { it.copy(stopSequence = value) }
                }
            }
        } else {
            Text(
                "Запросы независимы и не используют историю. После трёх ответов Sol " +
                    "выполнит слепую автооценку качества. Тарифы учтены на $MODEL_PRICE_DATE.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }

        Divider()
        SectionTitle("Готовые эксперименты")
        OutlinedButton(onClick = onReasoningDemo, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Демо: 4 способа рассуждения")
        }
        OutlinedButton(onClick = onTemperatureDemo, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Демо: температура")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConversationPanel(
    modifier: Modifier,
    exchanges: List<ConversationExchange>,
    operation: OperationState,
    notice: UiNotice?,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onDismissNotice: () -> Unit,
    onClearResults: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(exchanges.size, exchanges.lastOrNull()?.outcome) {
        if (exchanges.isNotEmpty()) listState.animateScrollToItem(exchanges.size)
    }

    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Рабочая область", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClearResults, enabled = exchanges.isNotEmpty() && operation !is OperationState.Running) {
                Text("Очистить экран")
            }
        }
        Divider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    if (exchanges.isEmpty()) WelcomeCard() else Spacer(Modifier.height(2.dp))
                }
                items(exchanges, key = ConversationExchange::id) { exchange ->
                    ExchangeView(exchange)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }

        notice?.let {
            NoticeBar(it, onDismissNotice)
        }
        if (operation is OperationState.Running) {
            ProgressPanel(operation, onCancel)
        }
        PromptComposer(
            value = prompt,
            onValueChange = onPromptChange,
            enabled = operation !is OperationState.Running,
            onSend = onSend,
        )
    }
}

@Composable
private fun WelcomeCard() {
    Card(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        elevation = 0.dp,
        border = BorderStroke(1.dp, Color(0xFFE2E6F0)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Начните с запроса", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Выберите провайдера, модель и режим слева. Ответы останутся на экране, " +
                    "поэтому можно продолжать диалог без перезапуска.",
                color = Muted,
            )
            Text("⌘/Ctrl + Enter — отправить", color = Primary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ExchangeView(exchange: ConversationExchange) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ЗАПРОС", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(exchange.mode.title(), color = Primary, fontSize = 11.sp)
        }
        Card(backgroundColor = Color(0xFFEEF1FF), elevation = 0.dp, shape = RoundedCornerShape(12.dp)) {
            SelectionContainer {
                Box(Modifier.padding(16.dp)) {
                    MarkdownBody(exchange.prompt)
                }
            }
        }
        when (val outcome = exchange.outcome) {
            ExchangeOutcome.Pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Ответ формируется…", color = Muted)
            }

            ExchangeOutcome.Cancelled -> StatusCard("Операция отменена пользователем.", Muted)
            is ExchangeOutcome.Failed -> StatusCard(outcome.message, Danger)
            is ExchangeOutcome.Completed -> ResultView(outcome.result)
        }
    }
}

@Composable
private fun ResultView(result: RequestResult) {
    when (result) {
        is RequestResult.Responses -> {
            ResponsiveCards(result.responses) { response ->
                ResponseCard(response.variant.heading, response.content)
            }
            Spacer(Modifier.height(10.dp))
            MetricsTable(result.responses.map { it.metrics() })
        }

        is RequestResult.Reasoning -> ReasoningView(result.report)
        is RequestResult.Temperature -> TemperatureView(result.report)
        is RequestResult.ModelComparison -> ModelComparisonView(result.report)
    }
}

@Composable
private fun ModelComparisonView(report: ModelComparisonReport) {
    Text("Сравнение моделей GPT-5.6", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text(
        "A/B/C — обозначения, переданные слепому оценщику; запросы выполнены последовательно.",
        color = Muted,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))
    ResponsiveCards(report.runs) { run ->
        when (val outcome = run.outcome) {
            is ModelCallOutcome.Success -> ResponseCard(
                "${run.target.answerLabel} · ${run.target.displayName.uppercase()} · ${run.target.tierLabel.uppercase()}",
                outcome.completion.content,
            )

            is ModelCallOutcome.Failure -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${run.target.answerLabel} · ${run.target.displayName.uppercase()}",
                    color = Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                StatusCard("Ошибка модели: ${outcome.message}", Danger)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    ModelComparisonMetricsTable(report)
    Spacer(Modifier.height(10.dp))
    when (val evaluation = report.evaluation?.outcome) {
        is ModelCallOutcome.Success -> ResponseCard(
            "СЛЕПАЯ АВТООЦЕНКА КАЧЕСТВА · GPT-5.6 SOL",
            evaluation.completion.content,
            accent = true,
        )

        is ModelCallOutcome.Failure -> StatusCard("Автооценка не выполнена: ${evaluation.message}", Danger)
        null -> StatusCard("Для автооценки нужны как минимум два успешных ответа.", Muted)
    }
    Text(
        "Автооценка помогает сравнить ответы, но не заменяет эталон или экспертную проверку.",
        color = Muted,
        fontSize = 12.sp,
    )
}

@Composable
private fun ModelComparisonMetricsTable(report: ModelComparisonReport) {
    val horizontal = rememberScrollState()
    val rows = report.runs + listOfNotNull(report.evaluation)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Скорость, токены и стоимость", fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.horizontalScroll(horizontal).width(960.dp)
                    .background(Color.White, RoundedCornerShape(10.dp)),
            ) {
                ModelMetricsRow(
                    "Прогон",
                    "Время",
                    "Input",
                    "Completion",
                    "Reasoning",
                    "Всего",
                    "Стоимость",
                    header = true,
                )
                Divider()
                rows.forEachIndexed { index, run ->
                    val usage = run.completion?.usage
                    ModelMetricsRow(
                        run = if (index < report.runs.size) {
                            "${run.target.answerLabel} · ${run.target.displayName}"
                        } else {
                            "Автооценка · ${run.target.displayName}"
                        },
                        elapsed = formatElapsed(run.elapsedMillis),
                        input = usage?.promptTokens?.toString() ?: "н/д",
                        completion = usage?.completionTokens?.toString() ?: "н/д",
                        reasoning = usage?.reasoningTokens?.toString() ?: "н/д",
                        total = usage?.totalTokens?.toString() ?: "н/д",
                        cost = run.estimatedCostUsd?.let(::formatUsd) ?: "н/д",
                    )
                    Divider(color = Color(0xFFF0F1F5))
                }
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontal),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
        Text(
            "Расчётная стоимость всего эксперимента: " +
                "${report.estimatedTotalCostUsd?.let(::formatUsd) ?: "н/д"} " +
                "· тарифы на $MODEL_PRICE_DATE",
            color = Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ModelMetricsRow(
    run: String,
    elapsed: String,
    input: String,
    completion: String,
    reasoning: String,
    total: String,
    cost: String,
    header: Boolean = false,
) {
    Row(Modifier.width(960.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
        val weight = if (header) FontWeight.Bold else FontWeight.Normal
        Text(run, Modifier.weight(2.4f), fontSize = 12.sp, fontWeight = weight)
        Text(elapsed, Modifier.weight(1f), fontSize = 12.sp, fontWeight = weight)
        Text(input, Modifier.weight(.8f), fontSize = 12.sp, fontWeight = weight)
        Text(completion, Modifier.weight(1f), fontSize = 12.sp, fontWeight = weight)
        Text(reasoning, Modifier.weight(1f), fontSize = 12.sp, fontWeight = weight)
        Text(total, Modifier.weight(.8f), fontSize = 12.sp, fontWeight = weight)
        Text(cost, Modifier.weight(1.2f), fontSize = 12.sp, fontWeight = weight)
    }
}

private fun formatElapsed(elapsedMillis: Long): String = if (elapsedMillis < 1_000) {
    "$elapsedMillis мс"
} else {
    "%.3f с".format(java.util.Locale.ROOT, elapsedMillis / 1_000.0).replace('.', ',')
}

private fun formatUsd(value: Double): String = "$" +
    "%.6f".format(java.util.Locale.ROOT, value).replace('.', ',')

@Composable
private fun ReasoningView(report: ReasoningReport) {
    Text("Четыре способа рассуждения", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    ResponsiveCards(report.solutions) { solution ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (solution.variant == org.example.app.ReasoningVariant.GENERATED_PROMPT) {
                ResponseCard("СГЕНЕРИРОВАННЫЙ ПРОМПТ", report.generatedPrompt, subtle = true)
            }
            ResponseCard(solution.variant.heading, solution.content)
        }
    }
    Spacer(Modifier.height(10.dp))
    MetricsTable(report.solutions.map { it.metrics() })
    Spacer(Modifier.height(10.dp))
    ResponseCard("СРАВНЕНИЕ И ОЦЕНКА ТОЧНОСТИ", report.evaluation.content, accent = true)
}

@Composable
private fun TemperatureView(report: TemperatureReport) {
    val models = report.samples.mapNotNull { it.completion.model }.distinct()
    if (models.isNotEmpty()) Text("Модель: ${models.joinToString()}", color = Muted, fontSize = 12.sp)
    ResponsiveCards(report.samples) { sample ->
        ResponseCard("TEMPERATURE = ${sample.temperature.label()}", sample.content)
    }
    Spacer(Modifier.height(10.dp))
    MetricsTable(report.samples.map { it.metrics() })
    Spacer(Modifier.height(10.dp))
    ResponseCard(
        "ВЫВОДЫ ПО ИСПОЛЬЗОВАНИЮ",
        report.evaluation.content,
        accent = true,
    )
}

@Composable
private fun <T> ResponsiveCards(items: List<T>, content: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp && items.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.chunked(2).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEach { content(it) }
            }
        }
    }
}

@Composable
private fun ResponseCard(
    title: String,
    body: String,
    subtle: Boolean = false,
    accent: Boolean = false,
) {
    val background = when {
        accent -> Color(0xFFF0F8F4)
        subtle -> Color(0xFFF8F9FC)
        else -> Color.White
    }
    val border = if (accent) Color(0xFFAFE0CA) else Color(0xFFE2E6F0)
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = background,
        elevation = 0.dp,
        border = BorderStroke(1.dp, border),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = if (accent) Success else Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            SelectionContainer {
                MarkdownBody(body)
            }
        }
    }
}

@Composable
private fun MarkdownBody(body: String) {
    val blocks = remember(body) { parseMarkdownBlocks(body) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Text -> Text(markdownAnnotatedString(block.value), lineHeight = 21.sp)
                is MarkdownBlock.Heading -> MarkdownHeading(block)
                is MarkdownBlock.Formula -> MarkdownFormula(block)
                is MarkdownBlock.Table -> MarkdownTable(block)
            }
        }
    }
}

@Composable
private fun MarkdownHeading(heading: MarkdownBlock.Heading) {
    val fontSize = when (heading.level) {
        1 -> 24.sp
        2 -> 21.sp
        3 -> 18.sp
        4 -> 16.sp
        else -> 14.sp
    }
    Text(
        markdownAnnotatedString(heading.value),
        fontSize = fontSize,
        lineHeight = fontSize * 1.3f,
        fontWeight = if (heading.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
    )
}

@Composable
private fun MarkdownFormula(formula: MarkdownBlock.Formula) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF8F9FC),
        elevation = 0.dp,
        border = BorderStroke(1.dp, Color(0xFFE2E6F0)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            formula.value,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {
    val horizontal = rememberScrollState()
    val columnWidths = remember(table) { markdownColumnWidths(table) }
    val tableWidth = columnWidths.fold(0.dp) { total, width -> total + width }

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.horizontalScroll(horizontal).width(tableWidth).padding(bottom = 10.dp),
        ) {
            MarkdownTableRow(table.headers, columnWidths, table.alignments, header = true)
            table.rows.forEach { row -> MarkdownTableRow(row, columnWidths, table.alignments) }
        }
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(horizontal),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    columnWidths: List<androidx.compose.ui.unit.Dp>,
    alignments: List<MarkdownColumnAlignment>,
    header: Boolean = false,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        cells.forEachIndexed { index, cell ->
            Box(
                Modifier.width(columnWidths[index]).fillMaxHeight()
                    .background(if (header) Color(0xFFE4F2EB) else Color.White)
                    .border(.5.dp, Color(0xFFCDD8D2))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    markdownAnnotatedString(cell),
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                    textAlign = alignments[index].textAlign(),
                )
            }
        }
    }
}

private fun MarkdownColumnAlignment.textAlign(): TextAlign = when (this) {
    MarkdownColumnAlignment.LEFT -> TextAlign.Start
    MarkdownColumnAlignment.CENTER -> TextAlign.Center
    MarkdownColumnAlignment.RIGHT -> TextAlign.End
}

private fun markdownColumnWidths(table: MarkdownBlock.Table): List<androidx.compose.ui.unit.Dp> =
    table.headers.indices.map { column ->
        val longestLine = (listOf(table.headers[column]) + table.rows.map { it[column] })
            .flatMap { it.lines() }
            .maxOfOrNull(String::length)
            ?: 0
        (longestLine * 7 + 24).coerceIn(96, 520).dp
    }

private fun markdownAnnotatedString(value: String): AnnotatedString {
    val normalized = normalizeMarkdownText(value)
    return buildAnnotatedString {
        var cursor = 0
        BOLD_MARKDOWN.findAll(normalized).forEach { match ->
            append(normalized.substring(cursor, match.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(match.groupValues[1])
            }
            cursor = match.range.last + 1
        }
        append(normalized.substring(cursor))
    }
}

private val BOLD_MARKDOWN = Regex("\\*\\*(.+?)\\*\\*")

@Composable
private fun MetricsTable(rows: List<ResponseMetrics>) {
    val horizontal = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Метрики", fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.horizontalScroll(horizontal).requiredWidthIn(min = 720.dp)
                    .background(Color.White, RoundedCornerShape(10.dp)),
            ) {
                MetricsRow("Прогон", "Символов", "Слов", "Токенов", "Finish reason", header = true)
                Divider()
                rows.forEach { metrics ->
                    MetricsRow(
                        metrics.run,
                        metrics.characterCount.toString(),
                        metrics.wordCount.toString(),
                        metrics.completionTokens?.toString() ?: "н/д",
                        metrics.finishReason ?: "н/д",
                    )
                    Divider(color = Color(0xFFF0F1F5))
                }
            }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontal),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MetricsRow(run: String, characters: String, words: String, tokens: String, finish: String, header: Boolean = false) {
    Row(Modifier.width(720.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
        val weight = if (header) FontWeight.Bold else FontWeight.Normal
        Text(run, Modifier.weight(2.2f), fontSize = 12.sp, fontWeight = weight)
        Text(characters, Modifier.weight(1f), fontSize = 12.sp, fontWeight = weight)
        Text(words, Modifier.weight(.8f), fontSize = 12.sp, fontWeight = weight)
        Text(tokens, Modifier.weight(1f), fontSize = 12.sp, fontWeight = weight)
        Text(finish, Modifier.weight(1.4f), fontSize = 12.sp, fontWeight = weight)
    }
}

@Composable
private fun NoticeBar(notice: UiNotice, onDismiss: () -> Unit) {
    val color = if (notice.kind == NoticeKind.ERROR) Danger else Success
    Surface(color = color.copy(alpha = .08f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(notice.message, Modifier.weight(1f), color = color, fontSize = 13.sp)
            TextButton(onClick = onDismiss) { Text("Закрыть", color = color) }
        }
    }
}

@Composable
private fun ProgressPanel(operation: OperationState.Running, onCancel: () -> Unit) {
    Surface(color = Color(0xFFFFF8E7)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(operation.progress.label, fontWeight = FontWeight.Medium)
                    Text(
                        if (operation.progress.current == 0) "Подготовка" else
                            "${operation.progress.current} из ${operation.progress.total}",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
                LinearProgressIndicator(
                    progress = operation.progress.fraction,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            OutlinedButton(onClick = onCancel) { Text("Отменить") }
        }
    }
}

@Composable
private fun PromptComposer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
) {
    Surface(elevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 58.dp, max = 150.dp)
                    .onPreviewKeyEvent { event ->
                        if (
                            event.type == KeyEventType.KeyDown && event.key == Key.Enter &&
                            (event.isCtrlPressed || event.isMetaPressed) && enabled && value.isNotBlank()
                        ) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    },
                enabled = enabled,
                label = { Text("Новый запрос") },
                placeholder = { Text("Напишите вопрос или задачу…") },
            )
            Button(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.height(52.dp).widthIn(min = 112.dp),
            ) {
                Text("Отправить")
            }
        }
    }
}

@Composable
private fun <T> DropdownSelector(
    label: String,
    selected: String,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.White),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, color = Muted, fontSize = 10.sp)
                Text(selected, color = MaterialTheme.colors.onSurface)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 280.dp),
        ) {
            options.forEach { (value, title) ->
                DropdownMenuItem(onClick = {
                    expanded = false
                    onSelected(value)
                }) {
                    Text(title)
                }
            }
        }
    }
}

@Composable
private fun NumericSetting(label: String, value: Int, enabled: Boolean, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newValue ->
            if (newValue.all(Char::isDigit)) {
                text = newValue
                newValue.toIntOrNull()?.takeIf { it > 0 }?.let(onValue)
            }
        },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        isError = text.toIntOrNull()?.let { it <= 0 } ?: true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StopSequenceSetting(value: String, enabled: Boolean, onValue: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { newValue ->
            if ('\n' !in newValue && '\r' !in newValue) {
                text = newValue
                if (newValue.isNotBlank()) onValue(newValue)
            }
        },
        label = { Text("Stop sequence") },
        enabled = enabled,
        singleLine = true,
        isError = text.isBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusCard(message: String, color: Color) {
    Card(backgroundColor = color.copy(alpha = .08f), elevation = 0.dp) {
        Text(message, Modifier.fillMaxWidth().padding(14.dp), color = color)
    }
}

private fun ResponseMode.title(): String = when (this) {
    ResponseMode.COMPARE -> "Сравнение двух ответов"
    ResponseMode.CONTROLLED -> "С ограничениями"
    ResponseMode.UNRESTRICTED -> "Без ограничений"
    ResponseMode.REASONING -> "4 способа рассуждения"
    ResponseMode.TEMPERATURE -> "Сравнение температуры"
    ResponseMode.MODEL_COMPARISON -> "Сравнение моделей GPT-5.6"
}
