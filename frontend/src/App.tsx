import {useEffect, useRef, useState} from "react";
import {ActionIcon, Alert, Badge, Button, Drawer, Group, Loader, Progress, Textarea, Tooltip,} from "@mantine/core";
import {
    IconArrowDown,
    IconArrowUp,
    IconBolt,
    IconLayoutColumns,
    IconMenu2,
    IconMessage,
    IconPlayerStop,
    IconRefresh,
    IconSparkles,
    IconTrash,
    IconWifiOff,
} from "@tabler/icons-react";
import {useWorkbench} from "./state/useWorkbench";
import {Sidebar} from "./components/Sidebar";
import {ExchangeView} from "./components/Results";

export default function App() {
  const w = useWorkbench();
  const [prompt, setPrompt] = useState("");
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [atBottom, setAtBottom] = useState(true);
  const feed = useRef<HTMLDivElement>(null);
  const composer = useRef<HTMLTextAreaElement>(null);
  const s = w.state;
  const scrollDown = () =>
    feed.current?.scrollTo({
      top: feed.current.scrollHeight,
      behavior: "smooth",
    });
  const lastId = s?.exchanges.at(-1)?.id;
  const count = s?.exchanges.at(-1)?.outputs.length;
  const streamedLength = s?.exchanges.at(-1)?.outputs.at(-1)?.content?.length;
  useEffect(() => {
    if (atBottom) scrollDown();
  }, [lastId, count, streamedLength, s?.operation?.progress.current]); // Keep position when reading previous answers.
  const locked = !!s?.operation || w.working || !w.connected || w.uncertain;
  const send = async () => {
    if (locked || !prompt.trim()) return;
    if (await w.start(prompt)) {
      setPrompt("");
      setAtBottom(true);
      scrollDown();
      composer.current?.focus();
    }
  };
  if (!s)
    return (
      <div className="loading-screen">
        <div className="brand-mark">
          <IconBolt size={26} />
        </div>
        <h1>LLM Workbench</h1>
        {w.error ? (
          <Alert color="red">
            {w.error}
            <Button mt="sm" variant="light" onClick={() => void w.refresh()}>
              Подключиться снова
            </Button>
          </Alert>
        ) : (
          <>
            <Loader size="sm" />
            <p>Подключение к локальному серверу…</p>
          </>
        )}
      </div>
    );
  const mode = s.modes.find((m) => m.id === s.settings.mode)!;
  return (
    <div className="app-shell">
      <header className="app-header">
        <Group gap={12}>
          <ActionIcon
            className="mobile-menu"
            variant="subtle"
            aria-label="Открыть настройки"
            onClick={() => setSidebarOpen(true)}
          >
            <IconMenu2 />
          </ActionIcon>
          <div className="brand-mark">
            <IconBolt size={22} />
          </div>
          <div className="brand-text">
            <strong>LLM Workbench</strong>
            <span>Лаборатория ответов</span>
          </div>
          <span className="local-label">LOCAL</span>
        </Group>
        <div className="header-status">
          <span
            className={`status-dot ${!w.connected ? "offline" : s.operation ? "busy" : ""}`}
          />
          <span>
            {!w.connected
              ? "Переподключение"
              : s.operation
                ? "Эксперимент идёт"
                : "Готов к работе"}
          </span>
          <span className="header-model">
            {mode.connectionLocked
              ? "OpenAI · Luna / Terra / Sol"
              : s.settings.model}
          </span>
        </div>
      </header>
      <div className="desktop-sidebar">
        <Sidebar workbench={w} />
      </div>
      <Drawer
        opened={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        title="Настройки эксперимента"
        size={330}
        padding={0}
        classNames={{ body: "drawer-body" }}
      >
        <Sidebar workbench={w} />
      </Drawer>
      <main className="workspace">
        <div className="workspace-heading">
          <div>
            <div className="eyebrow">Рабочее пространство</div>
            <h1>
              Диалоги и эксперименты{" "}
              <Badge size="sm" color="gray" variant="light">
                {s.exchanges.length}
              </Badge>
            </h1>
          </div>
          <Tooltip label="Удаляет карточки. История диалога сохраняется.">
            <Button
              variant="subtle"
              color="gray"
              size="xs"
              leftSection={<IconTrash size={15} />}
              disabled={locked || !s.exchanges.length}
              onClick={() => void w.clear("results")}
            >
              Очистить экран
            </Button>
          </Tooltip>
        </div>
        <div
          className="conversation"
          ref={feed}
          onScroll={(e) => {
            const el = e.currentTarget;
            setAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 100);
          }}
        >
          <div className="conversation-inner">
            {!s.exchanges.length ? (
              <div className="welcome">
                <div className="welcome-symbol">
                  <IconLayoutColumns size={34} stroke={1.4} />
                  <span>
                    <IconSparkles size={16} />
                  </span>
                </div>
                <Badge variant="light" size="sm">
                  ОДИН ЗАПРОС · НЕСКОЛЬКО ВЗГЛЯДОВ
                </Badge>
                <h2>
                  Хорошие ответы начинаются
                  <br />с эксперимента
                </h2>
                <p>
                  Сравните модели, ограничения и способы рассуждения.
                  <br />
                  Найдите подход, который работает для вашей задачи.
                </p>
                <div className="starter-grid">
                  <button
                    disabled={locked}
                    onClick={() => {
                      setPrompt(
                        "Объясни, как работает квантовый компьютер, простыми словами.",
                      );
                      composer.current?.focus();
                    }}
                  >
                    <IconMessage size={20} />
                    <strong>Объяснить сложное</strong>
                    <span>Простые слова или строгий формат</span>
                    <IconArrowUp size={16} />
                  </button>
                  <button
                    disabled={locked}
                    onClick={() => void w.start("", "reasoning")}
                  >
                    <IconSparkles size={20} />
                    <strong>Проверить рассуждение</strong>
                    <span>4 подхода к задаче о шкафчиках</span>
                    <IconArrowUp size={16} />
                  </button>
                  <button
                    disabled={locked}
                    onClick={() => void w.start("", "temperature")}
                  >
                    <IconBolt size={20} />
                    <strong>Найти креативность</strong>
                    <span>Один запрос, три температуры</span>
                    <IconArrowUp size={16} />
                  </button>
                </div>
                <p className="welcome-tip">
                  Выберите подключение слева и задайте свой первый вопрос
                </p>
              </div>
            ) : (
              s.exchanges.map((e) => (
                <ExchangeView
                  key={e.id}
                  exchange={e}
                  modes={s.modes}
                  priceDate={s.priceDate}
                />
              ))
            )}
          </div>
        </div>
        {!atBottom && s.exchanges.length > 0 && (
          <Tooltip label="К последнему ответу">
            <ActionIcon
              className="scroll-latest"
              size="lg"
              radius="xl"
              variant="filled"
              aria-label="К последнему ответу"
              onClick={scrollDown}
            >
              <IconArrowDown size={18} />
            </ActionIcon>
          </Tooltip>
        )}
        <div className="composer-zone">
          {!w.connected && (
            <Alert
              color="yellow"
              icon={<IconWifiOff size={18} />}
              title="Связь с сервером потеряна"
            >
              Переподключаемся автоматически. Генерация на сервере продолжается.
              <Button
                size="xs"
                variant="subtle"
                onClick={() => void w.refresh()}
                leftSection={<IconRefresh size={14} />}
              >
                Проверить связь
              </Button>
            </Alert>
          )}
          {w.error && (
            <Alert
              color="red"
              withCloseButton
              onClose={w.dismissError}
              closeButtonLabel="Закрыть ошибку"
            >
              {w.error}
            </Alert>
          )}
          {w.uncertain && (
            <Alert color="yellow" title="Ответ на команду не получен">
              Проверьте отправку: сервер узнает команду по её идентификатору и
              не запустит её повторно.
              <Button
                mt="xs"
                size="xs"
                onClick={async () => {
                  if (await w.start(prompt)) setPrompt("");
                }}
                disabled={!w.connected || w.working}
              >
                Проверить отправку
              </Button>
            </Alert>
          )}
          {s.notice && (
            <Alert
              color={s.notice.kind === "error" ? "red" : "teal"}
              withCloseButton
              onClose={() => void w.clear("notice")}
              closeButtonLabel="Закрыть уведомление"
            >
              {s.notice.message}
            </Alert>
          )}
          {s.operation && (
            <div className="operation-panel" role="status">
              <div className="operation-top">
                <Group gap={8}>
                  <Loader size="xs" />
                  <strong>{s.operation.progress.label}</strong>
                </Group>
                <span>
                  Этап {s.operation.progress.current} из{" "}
                  {s.operation.progress.total}
                </span>
                <Button
                  size="xs"
                  color="red"
                  variant="light"
                  leftSection={<IconPlayerStop size={14} />}
                  disabled={w.working || !w.connected}
                  onClick={() => void w.cancel()}
                >
                  Отменить
                </Button>
              </div>
              <Progress
                value={
                  (100 * Math.max(0, s.operation.progress.current - 1)) /
                  s.operation.progress.total
                }
                animated
                size={4}
              />
            </div>
          )}
          <div className={`composer ${s.operation ? "composer-running" : ""}`}>
            <div className="composer-label">
              <IconMessage size={15} />
              <span>Новый запрос</span>
              <Badge variant="light" size="xs">
                {mode.title}
              </Badge>
            </div>
            <Textarea
              ref={composer}
              aria-label="Новый запрос"
              placeholder="Напишите вопрос, идею или задачу…"
              value={prompt}
              onChange={(e) => setPrompt(e.currentTarget.value)}
              autosize
              minRows={2}
              maxRows={7}
              maxLength={100000}
              disabled={!!s.operation || w.uncertain}
              variant="unstyled"
              onKeyDown={(e) => {
                if (
                  e.key === "Enter" &&
                  (e.ctrlKey || e.metaKey) &&
                  !e.nativeEvent.isComposing
                ) {
                  e.preventDefault();
                  void send();
                }
              }}
            />
            <div className="composer-bottom">
              <span>
                <kbd>⌘</kbd> / <kbd>Ctrl</kbd> + <kbd>Enter</kbd>
                <span className="composer-context">
                  {" "}
                  ·{" "}
                  {mode.independentContext
                    ? "Независимые контексты"
                    : s.settings.historyEnabled
                      ? "История включена"
                      : "Без истории"}
                </span>
              </span>
              <Button
                leftSection={<IconArrowUp size={17} />}
                disabled={locked || !prompt.trim()}
                onClick={() => void send()}
              >
                Отправить
              </Button>
            </div>
          </div>
          <p className="composer-footnote">
            Ответы могут содержать ошибки. Проверяйте важную информацию.
          </p>
        </div>
      </main>
    </div>
  );
}
