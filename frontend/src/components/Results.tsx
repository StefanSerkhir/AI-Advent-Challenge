import {useState} from "react";
import {ActionIcon, Alert, Badge, Button, Tooltip,} from "@mantine/core";
import {IconAlertCircle, IconCheck, IconChevronDown, IconChevronUp, IconCopy, IconSparkles,} from "@tabler/icons-react";
import type {Exchange, ModeInfo, Output} from "../api/types";
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
  },
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
export function ExchangeView({
  exchange: e,
  modes,
  priceDate,
}: {
  exchange: Exchange;
  modes: ModeInfo[];
  priceDate: string;
}) {
  const responses = e.outputs.filter((o) => o.kind === "response");
  const prompts = e.outputs.filter((o) => o.kind === "prompt");
  const evaluation = e.outputs.filter((o) => o.kind === "evaluation");
  const hasVisibleStream = [...responses, ...evaluation].some(
    (o) => o.streaming,
  );
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
