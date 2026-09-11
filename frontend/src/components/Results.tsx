import {useState} from "react";
import {ActionIcon, Alert, Badge, Button, Tooltip,} from "@mantine/core";
import {IconAlertCircle, IconCheck, IconChevronDown, IconChevronUp, IconCopy, IconSparkles,} from "@tabler/icons-react";
import type {
  ConversationTokenTotals,
  Exchange,
  ModeInfo,
  Output,
  TokenConversation,
  TurnTokenMetrics
} from "../api/types";
import {Markdown} from "./Markdown";

const number = (n: number | null) =>
  n === null ? "—" : n.toLocaleString("ru-RU");
const cost = (n: number | null) => (n === null ? "н/д" : `$${n.toFixed(6)}`);
const counted = (count: number, one: string, few: string, many: string) => {
  const mod100 = count % 100;
  const mod10 = count % 10;
  const word =
    mod100 >= 11 && mod100 <= 14
      ? many
      : mod10 === 1
        ? one
        : mod10 >= 2 && mod10 <= 4
          ? few
          : many;
  return `${count} ${word}`;
};
const strategyTitle = (strategy: NonNullable<Output["contextStrategy"]>) => ({
  SLIDING_WINDOW: "Скользящее окно",
  STICKY_FACTS: "Закреплённые факты",
  BRANCHING: "Ветвление",
})[strategy];

const thinkingOutput: Output = {
  id: "thinking",
  title: "ОТВЕТ",
  kind: "response",
  content: null,
  error: null,
  model: null,
  streaming: true,
  metrics: {
    characters: null,
    words: null,
    completionTokens: null,
    finishReason: null,
    promptTokens: null,
    reasoningTokens: null,
    totalTokens: null,
    elapsedMillis: null,
    estimatedCostUsd: null,
    cachedInputTokens: null,
    cacheWriteInputTokens: null,
  },
  tokenMetrics: null,
  contextStrategy: null,
  branchId: null,
  branchName: null,
};

function ResponseCard({ output }: { output: Output }) {
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState(false);
  const long = !output.streaming && (output.content?.length ?? 0) > 1800;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(output.content ?? "");
      setCopied(true);
      setCopyError(false);
    } catch {
      setCopyError(true);
    }
  };
  return (
    <article
      className={`response-card ${output.kind} ${output.error ? "has-error" : ""}`}
      data-testid="response-card"
    >
      <div className="card-heading">
        <h3>
          {output.kind === "evaluation" && <IconSparkles size={16} />}{" "}
          {output.title}
        </h3>
        {output.contextStrategy && <Badge size="xs" variant="light">
          {strategyTitle(output.contextStrategy)}{output.branchName ? ` · ${output.branchName} (${output.branchId})` : ""}
        </Badge>}
        {output.content && (
          <Tooltip label={copied ? "Скопировано" : "Копировать ответ"}>
            <ActionIcon
              variant="subtle"
              color="gray"
              aria-label="Копировать ответ"
              onClick={() => void copy()}
            >
              {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
            </ActionIcon>
          </Tooltip>
        )}
      </div>
      {output.model && <div className="model-label">{output.model}</div>}
      {output.error ? (
        <Alert
          color="red"
          icon={<IconAlertCircle size={18} />}
          title="Ошибка модели"
        >
          {output.error}
        </Alert>
      ) : (
        <div
          className={`response-body ${long && !expanded ? "collapsed" : ""} ${output.streaming ? "streaming" : ""}`}
        >
          {output.streaming && !output.content ? (
            <span className="thinking-text">Думаю</span>
          ) : (
            <Markdown>{output.content ?? ""}</Markdown>
          )}
          {output.streaming && (
            <span
              className="streaming-cursor"
              aria-label="Ответ генерируется"
              role="status"
            />
          )}
        </div>
      )}
      {long && (
        <Button
          size="xs"
          variant="subtle"
          leftSection={
            expanded ? (
              <IconChevronUp size={14} />
            ) : (
              <IconChevronDown size={14} />
            )
          }
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? "Свернуть ответ" : "Развернуть ответ"}
        </Button>
      )}
      {copyError && (
        <p role="status" className="micro">
          Копирование недоступно. Выделите текст ответа вручную.
        </p>
      )}
    </article>
  );
}
function MetricsTable({
  outputs,
  models,
  agent,
}: {
  outputs: Output[];
  models: boolean;
  agent: boolean;
}) {
  return (
    <details className="metrics" open>
      <summary>
        {agent ? (
          "Метрики ответа"
        ) : (
          <>
            Метрики{" "}
            <span>
              {models
                ? counted(outputs.length, "вызов", "вызова", "вызовов")
                : counted(outputs.length, "вариант", "варианта", "вариантов")}
            </span>
          </>
        )}
      </summary>
      <div
        className="table-scroll"
        tabIndex={0}
        role="region"
        aria-label={agent ? "Метрики ответа агента" : "Таблица метрик"}
      >
        <table>
          <thead>
            <tr>
              <th>{agent ? "Ответ" : "Вариант"}</th>
              <th>Символы</th>
              <th>Слова</th>
              {models && <th>Время, с</th>}
              <th>Input</th>
              <th>Completion</th>
              {models && <th>Reasoning</th>}
              <th>Total</th>
              <th>Finish reason</th>
              {models && <th>Стоимость, USD</th>}
            </tr>
          </thead>
          <tbody>
            {outputs.map((o) => (
              <tr key={o.id}>
                <th>
                  {o.title}
                  {o.error && (
                    <Badge color="red" size="xs" ml={8}>
                      Ошибка
                    </Badge>
                  )}
                </th>
                <td>{number(o.metrics.characters)}</td>
                <td>{number(o.metrics.words)}</td>
                {models && (
                  <td>
                    {o.metrics.elapsedMillis === null
                      ? "—"
                      : (o.metrics.elapsedMillis / 1000).toFixed(2)}
                  </td>
                )}
                <td>{number(o.metrics.promptTokens)}</td>
                <td>{number(o.metrics.completionTokens)}</td>
                {models && <td>{number(o.metrics.reasoningTokens)}</td>}
                <td>{number(o.metrics.totalTokens)}</td>
                <td>
                  <code>{o.metrics.finishReason ?? "—"}</code>
                </td>
                {models && <td>{cost(o.metrics.estimatedCostUsd)}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

const decimalCost = (value: string | null) =>
  value === null ? "н/д" : `$${Number(value).toLocaleString("ru-RU", { minimumFractionDigits: 6, maximumFractionDigits: 10 })}`;
const tokenValue = (value: { value: number | null }) =>
  value.value === null ? "н/д" : value.value.toLocaleString("ru-RU");
const sourceTitle = (source: string) =>
  source.includes("actual") ? "Фактическое значение из usage API" :
    source === "model_profile" ? "Значение профиля модели" :
      source === "request" ? "Параметр API-запроса" : "Расчётное значение (estimate)";

function ContextMeter({ metrics: m }: { metrics: TurnTokenMetrics }) {
  const percent = m.contextUsagePercent.value === null ? null : Number(m.contextUsagePercent.value);
  const level = percent === null ? "unknown" : percent >= 95 ? "critical" : percent >= 85 ? "danger" : percent >= 70 ? "warning" : "safe";
  const warning = percent === null ? "Контекстный лимит модели неизвестен: локальная блокировка не применяется." :
    percent >= 95 ? "Заполнено ≥95% input-бюджета: запрос почти наверняка требует сокращения." :
      percent >= 85 ? "Заполнено ≥85% input-бюджета: высокий риск переполнения." :
        percent >= 70 ? "Заполнено ≥70% input-бюджета: следите за ростом истории." : null;
  return (
    <div className="context-meter">
      <div className="context-meter-label">
        <span>Заполнение input-бюджета <sup title="estimate">est.</sup></span>
        <strong>{percent === null ? "н/д" : `${percent.toLocaleString("ru-RU", { maximumFractionDigits: 1 })}%`}</strong>
      </div>
      <div className={`context-track ${level}`} role="progressbar" aria-label="Заполнение контекстного бюджета" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent === null ? undefined : Math.min(percent, 100)}>
        <span style={{ width: `${Math.min(percent ?? 0, 100)}%` }} />
      </div>
      {warning && <p className={`context-warning ${level}`}>{warning}</p>}
    </div>
  );
}

function TurnMetricsCard({ metrics: m }: { metrics: TurnTokenMetrics }) {
  const usageAvailable = m.actualInputTokens.value !== null;
  return (
    <section className="turn-metrics" aria-label={`Токенные метрики хода ${m.turnNumber}`}>
      <div className="token-section-heading">
        <div><strong>Ход {m.turnNumber}</strong><code>{m.model}</code></div>
        <Badge color={usageAvailable ? "teal" : "gray"}>{usageAvailable ? "usage API получен" : "usage н/д"}</Badge>
      </div>
      {m.tokenizerWarning && <Alert color="yellow" title="Приблизительный tokenizer">{m.tokenizerWarning}</Alert>}
      {m.contextProfileSimulated && <Alert color="blue">Симуляция уменьшенного окна. Лимит 6K не является реальным лимитом GPT‑5.6 Sol.</Alert>}
      <div className="token-kpis">
        {[
          ["Текущее сообщение", m.estimatedCurrentMessageTokens, "est."],
          ["История до сообщения", m.estimatedHistoryTokens, "est."],
          ["Контекст API", m.estimatedContextTokens, "est."],
          ["API input", m.actualInputTokens, "actual"],
          ["Output", m.actualOutputTokens, "actual"],
          ["Reasoning", m.reasoningTokens, "actual"],
          ["Cached input", m.cachedInputTokens, "actual"],
          ["Cache write", m.cacheWriteInputTokens, "actual"],
          ["Total", m.totalTokens, "actual"],
        ].map(([label, metric, marker]) => {
          const value = metric as TurnTokenMetrics["actualInputTokens"];
          return <div key={label as string} title={sourceTitle(value.source)}><span>{label as string} <sup>{marker as string}</sup></span><strong>{tokenValue(value)}</strong></div>;
        })}
      </div>
      <ContextMeter metrics={m} />
      <dl className="token-details">
        <div><dt>Контекстное окно <sup>profile</sup></dt><dd>{tokenValue(m.contextWindow)}</dd></div>
        <div><dt>Requested max output <sup>request</sup></dt><dd>{tokenValue(m.requestedMaxOutputTokens)}</dd></div>
        <div><dt>Резерв output <sup>est.</sup></dt><dd>{tokenValue(m.reservedOutputTokens)}</dd></div>
        <div><dt>Доступный input-бюджет <sup>est.</sup></dt><dd>{tokenValue(m.availableInputTokens)}</dd></div>
        <div><dt>Осталось input <sup>est.</sup></dt><dd>{tokenValue(m.estimatedRemainingInputTokens)}</dd></div>
        <div><dt>Finish reason <sup>actual</sup></dt><dd><code>{m.finishReason ?? "н/д"}</code></dd></div>
        <div><dt>Стоимость хода <sup>actual usage</sup></dt><dd>{decimalCost(m.turnCostUsd.value)}</dd></div>
        <div><dt>Политика переполнения <sup>request</sup></dt><dd><code>{m.overflowPolicy}</code></dd></div>
      </dl>
      {(m.excludedMessageCount.value ?? 0) > 0 && <Alert color="orange" title="Активный контекст сокращён">
        Исключено {tokenValue(m.excludedMessageCount)} сообщений (~{tokenValue(m.excludedEstimatedTokens)} токенов estimate). Постоянная история не удалена.
      </Alert>}
      {m.finishReason === "length" && <Alert color="yellow" title="Исчерпан output-лимит">
        finish_reason=length означает остановку генерации по лимиту ответа и не является переполнением входного контекста.
      </Alert>}
      {(m.exceededByTokens.value ?? 0) > 0 && <Alert color={m.actualInputTokens.value === null ? "red" : "orange"} title={m.actualInputTokens.value === null ? "Контекст превышен" : "До применения политики контекст был превышен"}>
        Исходно требуется {tokenValue(m.requiredTokens)} input-токенов estimate; превышение — {tokenValue(m.exceededByTokens)}.
      </Alert>}
      {!m.pricingProfileId && <Alert color="gray">Ценовой профиль неизвестен: стоимость недоступна, usage токенов остаётся фактическим.</Alert>}
      {m.pricingProfileId && <p className="pricing-caption">Профиль: <code>{m.pricingProfileId}</code> · актуален {m.pricingEffectiveDate} · <a href={m.pricingSourceUrl ?? undefined} target="_blank" rel="noreferrer">официальный источник</a></p>}
    </section>
  );
}

function ConversationSummary({ totals: t }: { totals: ConversationTokenTotals }) {
  const items: [string, { value: number | null } | null, string?][] = [
    ["Текущий размер истории", t.currentHistoryTokens, "est."],
    ["Cumulative API input", t.cumulativeApiInputTokens, "actual"],
    ["Cumulative output", t.cumulativeOutputTokens, "actual"],
    ["Cumulative reasoning", t.cumulativeReasoningTokens, "actual"],
    ["Cumulative total", t.cumulativeTotalTokens, "actual"],
    ["Cumulative cached input", t.cumulativeCachedInputTokens, "actual"],
    ["Cumulative cache write", t.cumulativeCacheWriteInputTokens, "actual"],
    ["Завершённых ходов", t.completedTurns],
    ["Сокращений контекста", t.contextTruncations],
  ];
  return <div className="conversation-summary" aria-label="Сводные токенные показатели диалога">
    {items.map(([label, value, marker]) => <div key={label}><span>{label} {marker && <sup>{marker}</sup>}</span><strong>{value ? tokenValue(value) : "н/д"}</strong></div>)}
    <div><span>Общая стоимость <sup>actual usage</sup></span><strong>{decimalCost(t.cumulativeCostUsd.value)}</strong></div>
    <p>{t.scope === "saved_conversation" ? "Сохранённый диалог" : "Runtime-сессия без сохранения истории"}</p>
  </div>;
}

export function TokenDashboard({ conversation }: { conversation: TokenConversation }) {
  const turns = conversation.turns;
  const maxInput = Math.max(1, ...turns.map((t) => t.actualInputTokens.value ?? 0));
  const maxCost = Math.max(0.0000000001, ...turns.map((t) => Number(t.cumulativeTotals.cumulativeCostUsd.value ?? 0)));
  return <section className="token-dashboard" aria-label="Рост токенов и стоимости">
    <h3>Токены, контекст и стоимость диалога</h3>
    <ConversationSummary totals={conversation.totals} />
    <div className="growth-chart" role="img" aria-label="Визуализация роста API input и накопленной стоимости по ходам">
      {turns.map((t) => <div className="growth-row" key={t.id}>
        <span>Ход {t.turnNumber}</span>
        <div className="growth-bars"><i className="input-bar" style={{ width: `${100 * (t.actualInputTokens.value ?? 0) / maxInput}%` }} /><i className="cost-bar" style={{ width: `${100 * Number(t.cumulativeTotals.cumulativeCostUsd.value ?? 0) / maxCost}%` }} /></div>
      </div>)}
      <p><span className="legend-input" /> API input actual <span className="legend-cost" /> Стоимость cumulative</p>
    </div>
    <div className="table-scroll token-growth-table" tabIndex={0} role="region" aria-label="Таблица роста токенов по ходам">
      <table><thead><tr><th>Ход</th><th>Запрос estimate</th><th>История estimate</th><th>Контекст estimate</th><th>API input</th><th>Output</th><th>Reasoning</th><th>Cached</th><th>Контекст %</th><th>Стоимость хода</th><th>Стоимость накопительно</th></tr></thead>
      <tbody>{turns.map((t) => <tr key={t.id}><td>{t.turnNumber}</td><td>{tokenValue(t.estimatedCurrentMessageTokens)}</td><td>{tokenValue(t.estimatedHistoryTokens)}</td><td>{tokenValue(t.estimatedContextTokens)}</td><td>{tokenValue(t.actualInputTokens)}</td><td>{tokenValue(t.actualOutputTokens)}</td><td>{tokenValue(t.reasoningTokens)}</td><td>{tokenValue(t.cachedInputTokens)}</td><td>{t.contextUsagePercent.value === null ? "н/д" : `${Number(t.contextUsagePercent.value).toFixed(1)}%`}</td><td>{decimalCost(t.turnCostUsd.value)}</td><td>{decimalCost(t.cumulativeTotals.cumulativeCostUsd.value)}</td></tr>)}</tbody></table>
    </div>
  </section>;
}

export function TokenMetricsPanel({ conversation }: { conversation: TokenConversation }) {
  const turns = conversation.turns;
  if (turns.length === 0) return null;
  const lastTurn = turns.at(-1)!;
  return <details className="token-metrics-panel" data-testid="token-metrics-panel">
    <summary>
      <span>Токены, контекст и стоимость</span>
      <span>{counted(turns.length, "ход", "хода", "ходов")} · последний: ход {lastTurn.turnNumber}</span>
    </summary>
    <div className="token-metrics-panel-content">
      {turns.map((turn) => <TurnMetricsCard metrics={turn} key={turn.id} />)}
      <TokenDashboard conversation={conversation} />
    </div>
  </details>;
}

export function ExchangeView({
  exchange: e,
  modes,
  priceDate,
  showTokenMetrics = false,
  tokenConversation,
}: {
  exchange: Exchange;
  modes: ModeInfo[];
  priceDate: string;
  showTokenMetrics?: boolean;
  tokenConversation?: TokenConversation;
}) {
  const responses = e.outputs.filter((o) => o.kind === "response" || o.kind === "token-turn");
  const prompts = e.outputs.filter((o) => o.kind === "prompt");
  const evaluation = e.outputs.filter((o) => o.kind === "evaluation");
  const hasVisibleStream = [...responses, ...evaluation].some(
    (o) => o.streaming,
  );
  const responseTurns = responses.flatMap((o) => o.tokenMetrics ? [o.tokenMetrics] : []);
  const responseConversation = responseTurns.length > 0 ? {
    turns: responseTurns,
    totals: responseTurns.at(-1)!.cumulativeTotals,
  } : undefined;
  const mergedSavedConversation = tokenConversation ? {
    turns: [
      ...tokenConversation.turns,
      ...responseTurns.filter((turn) => !tokenConversation.turns.some((saved) => saved.id === turn.id)),
    ],
    totals: responseTurns.at(-1)?.cumulativeTotals ?? tokenConversation.totals,
  } : undefined;
  const displayedTokenConversation = e.mode === "tokens"
    ? responseConversation
    : e.mode === "unrestricted" && showTokenMetrics
      ? (responseTurns.at(-1)?.cumulativeTotals.scope === "runtime_without_history"
        ? responseConversation
        : mergedSavedConversation ?? responseConversation)
      : undefined;
  const title = modes.find((m) => m.id === e.mode)?.title ?? e.mode;
  return (
    <section
      className="exchange"
      id={`exchange-${e.id}`}
      data-testid="exchange"
    >
      <div className="exchange-heading">
        <div>
          <span className="request-number">
            {String(e.id).padStart(2, "0")}
          </span>
          <span>{title}</span>
        </div>
        <Badge
          variant="light"
          color={
            {
              pending: "yellow",
              completed: "teal",
              failed: "red",
              cancelled: "gray",
            }[e.status]
          }
        >
          {
            {
              pending: "Выполняется",
              completed: "Завершено",
              failed: "Ошибка",
              cancelled: "Отменено",
            }[e.status]
          }
        </Badge>
      </div>
      <div className="user-prompt">
        <span className="eyebrow">Ваш запрос</span>
        <Markdown>{e.prompt}</Markdown>
      </div>
      {prompts.map((o) => (
        <details className="generated-prompt" key={o.id}>
          <summary>Сгенерированный промпт</summary>
          <ResponseCard output={o} />
        </details>
      ))}
      <div className="response-grid">
        {responses.map((o) => (
          <ResponseCard output={o} key={o.id} />
        ))}
        {e.status === "pending" && !hasVisibleStream && (
          <ResponseCard output={thinkingOutput} />
        )}
      </div>
      {responses.length > 0 && e.status !== "pending" && (
        <MetricsTable
          outputs={
            e.mode === "models" ? [...responses, ...evaluation] : responses
          }
          models={e.mode === "models"}
          agent={e.mode === "unrestricted"}
        />
      )}
      {evaluation.map((o) => (
        <ResponseCard output={o} key={o.id} />
      ))}
      {e.mode === "models" && e.status === "completed" && (
        <p className="micro">
          Всего по успешным вызовам:{" "}
          <strong>{cost(e.estimatedTotalCostUsd)}</strong> · Тарифы на{" "}
          {priceDate}. Автооценка не заменяет экспертную проверку.
        </p>
      )}
      {e.evaluationNote && <Alert color="yellow">{e.evaluationNote}</Alert>}
      {displayedTokenConversation && <TokenMetricsPanel conversation={displayedTokenConversation} />}
      {e.sentinelInPermanentHistory !== null && <Alert color="blue">Контрольный сентинел: в постоянной истории — {e.sentinelInPermanentHistory ? "да" : "нет"}; в активном API-контексте после DROP_OLDEST — {e.sentinelInActiveContext ? "да" : "нет"}.</Alert>}
      {e.status === "cancelled" && (
        <Alert color="gray">
          Операция отменена. Полученные ответы сохранены. Можно отправить новый
          запрос.
        </Alert>
      )}
      {e.error && (
        <Alert
          color="red"
          icon={<IconAlertCircle size={18} />}
          title="Не удалось завершить эксперимент"
        >
          {e.error}
        </Alert>
      )}
    </section>
  );
}
