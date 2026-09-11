import {useEffect, useState} from "react";
import {Badge, Button, Divider, NativeSelect, PasswordInput, Switch, TextInput, Tooltip,} from "@mantine/core";
import {
    IconAdjustments,
    IconArrowRight,
    IconFlask,
    IconHistory,
    IconKey,
    IconPlugConnected,
    IconTrash,
} from "@tabler/icons-react";
import type {Workbench} from "../state/useWorkbench";
import type {ContextStrategy, Mode, Provider} from "../api/types";

function NumberSetting({
  label,
  value,
  disabled,
  onSave,
}: {
  label: string;
  value: number;
  disabled: boolean;
  onSave: (value: number) => void;
}) {
  const [draft, setDraft] = useState(String(value));
  useEffect(() => setDraft(String(value)), [value]);
  const valid =
    /^\d+$/.test(draft) && Number(draft) > 0 && Number(draft) <= 2147483647;
  return (
    <TextInput
      label={label}
      value={draft}
      type="text"
      inputMode="numeric"
      disabled={disabled}
      onChange={(e) => setDraft(e.currentTarget.value)}
      error={!valid ? "Целое число больше нуля" : undefined}
      onBlur={() => {
        if (valid && Number(draft) !== value) onSave(Number(draft));
      }}
      onKeyDown={(e) => {
        if (e.key === "Enter") e.currentTarget.blur();
      }}
    />
  );
}
export function Sidebar({ workbench: w }: { workbench: Workbench }) {
  const s = w.state!;
  const mode = s.modes.find((m) => m.id === s.settings.mode)!;
  const provider = s.providers.find((p) => p.id === s.settings.provider)!;
  const locked = !!s.operation || w.working || !w.connected || w.uncertain;
  const [key, setKey] = useState("");
  const [stop, setStop] = useState(s.settings.stopSequence ?? "");
  useEffect(() => setKey(""), [s.settings.provider]);
  useEffect(
    () => setStop(s.settings.stopSequence ?? ""),
    [s.settings.stopSequence],
  );
  return (
    <aside className="sidebar" aria-label="Настройки эксперимента">
      <section>
        <div className="section-label">
          <IconPlugConnected size={16} /> Подключение{" "}
          <Badge
            size="xs"
            color={provider.hasKey ? "teal" : "gray"}
            variant="dot"
          >
            {provider.hasKey ? "Ключ настроен" : "Нет ключа"}
          </Badge>
        </div>
        <NativeSelect
          label="Провайдер"
          value={s.settings.provider}
          disabled={locked || mode.connectionLocked}
          data={s.providers.map((p) => ({ value: p.id, label: p.title }))}
          onChange={(e) =>
            void w.settings({ provider: e.currentTarget.value as Provider })
          }
        />
        <NativeSelect
          label="Модель"
          value={s.settings.model}
          disabled={locked || mode.connectionLocked}
          data={provider.models.map((m) => ({ value: m.id, label: m.title }))}
          onChange={(e) => void w.settings({ model: e.currentTarget.value })}
        />
        <PasswordInput
          label="API-ключ"
          placeholder={
            provider.hasKey
              ? "Сохранён · введите для замены"
              : "Введите ключ провайдера"
          }
          value={key}
          onChange={(e) => setKey(e.currentTarget.value)}
          disabled={locked}
          autoComplete="off"
          spellCheck={false}
        />
        <Button
          fullWidth
          variant="light"
          leftSection={<IconKey size={15} />}
          disabled={locked || !key.trim()}
          onClick={async () => {
            if (await w.saveKey(provider.id, key)) setKey("");
          }}
        >
          {provider.hasKey ? "Заменить ключ" : "Сохранить ключ"}
        </Button>
        <p className="micro">Ключ хранится только в локальном .env</p>
      </section>
      <Divider />
      <section>
        <div className="section-label">
          <IconAdjustments size={16} /> Эксперимент
        </div>
        <NativeSelect
          label="Режим ответа"
          value={s.settings.mode}
          disabled={locked}
          data={s.modes.map((m) => ({ value: m.id, label: m.title }))}
          onChange={(e) =>
            void w.settings({ mode: e.currentTarget.value as Mode })
          }
        />
        <p className="mode-description">{mode.description}</p>
        {mode.usesTokenLimit && (
          <NumberSetting
            label="Максимум токенов"
            value={s.settings.maxTokens}
            disabled={locked}
            onSave={(value) => void w.settings({ maxTokens: value })}
          />
        )}
        {mode.usesTextConstraints && (
          <>
            <div className="settings-pair">
              <NumberSetting
                label="Максимум слов"
                value={s.settings.maxWords}
                disabled={locked}
                onSave={(value) => void w.settings({ maxWords: value })}
              />
              <NumberSetting
                label="Пунктов"
                value={s.settings.bulletCount}
                disabled={locked}
                onSave={(value) => void w.settings({ bulletCount: value })}
              />
            </div>
            <Switch
              label="Stop sequence"
              checked={s.settings.stopSequence !== null}
              disabled={locked}
              onChange={(e) =>
                void w.settings({
                  stopSequence: e.currentTarget.checked
                    ? "<END_OF_RESPONSE>"
                    : null,
                })
              }
            />
            {s.settings.stopSequence !== null && (
              <TextInput
                aria-label="Stop sequence"
                value={stop}
                disabled={locked}
                onChange={(e) => setStop(e.currentTarget.value)}
                error={!stop.trim() ? "Введите stop sequence" : undefined}
                onBlur={() => {
                  if (stop.trim() && stop !== s.settings.stopSequence)
                    void w.settings({ stopSequence: stop });
                }}
              />
            )}
          </>
        )}
      </section>
      {mode.usesHistory && (
        <>
          <Divider />
          <section>
            <div className="section-label">
              <IconHistory size={16} /> Контекст
            </div>
            <Switch
              label="История диалога"
              checked={s.settings.historyEnabled}
              disabled={locked}
              onChange={(e) =>
                void w.settings({ historyEnabled: e.currentTarget.checked })
              }
            />
            {mode.id === "unrestricted" && (
              <>
                <NativeSelect
                  label="Стратегия контекста"
                  aria-label="Стратегия контекста"
                  value={s.settings.contextStrategy}
                  disabled={locked || !s.settings.historyEnabled}
                  onChange={(e) =>
                    void w.settings({
                      contextStrategy: e.currentTarget.value as ContextStrategy,
                    })
                  }
                  data={[
                    { value: "SLIDING_WINDOW", label: "Скользящее окно (Sliding Window)" },
                    { value: "STICKY_FACTS", label: "Закреплённые факты (Key-Value)" },
                    { value: "BRANCHING", label: "Ветвление (Branching)" },
                  ]}
                />
                {s.settings.contextStrategy !== "BRANCHING" ? (
                    <NumberSetting
                      label="Последних сообщений (N)"
                      value={s.settings.recentMessagesLimit}
                      disabled={locked || !s.settings.historyEnabled}
                      onSave={(value) =>
                        void w.settings({ recentMessagesLimit: value })
                      }
                    />
                ) : (
                  <div className="branch-controls" data-testid="branch-controls">
                    {!s.context.checkpoint && (
                      <Button size="xs" variant="light" disabled={locked || !s.settings.historyEnabled}
                        onClick={() => void w.checkpoint()}>Создать checkpoint</Button>
                    )}
                    {s.context.checkpoint && <p className="micro">Checkpoint: <code>{s.context.checkpoint.id}</code></p>}
                    {s.context.branches.map((branch) => (
                      <Button key={branch.id} size="xs"
                        variant={branch.id === s.context.activeBranchId ? "filled" : "default"}
                        disabled={locked || !s.settings.historyEnabled}
                        onClick={() => void w.switchBranch(branch.id)}>
                        {branch.name} · {branch.id.slice(0, 8)}
                      </Button>
                    ))}
                  </div>
                )}
                {s.settings.contextStrategy === "STICKY_FACTS" && (
                  <div className="facts-panel" data-testid="facts-panel">
                    <strong>Текущие facts</strong>
                    {Object.keys(s.context.facts).length === 0 ? <p className="micro">Пока пусто</p> :
                      <dl>{Object.entries(s.context.facts).map(([key, value]) =>
                        <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl>}
                    <p className="micro">Извлечение: {s.context.factUsage.requests} выз.; {s.context.factUsage.totalTokens} токенов</p>
                  </div>
                )}
              </>
            )}
            <NativeSelect
              label="Переполнение контекста"
              value={s.settings.contextOverflowPolicy}
              disabled={locked}
              data={[
                { value: "REJECT", label: "REJECT — отклонить" },
                { value: "DROP_OLDEST", label: "DROP_OLDEST — убрать старые пары" },
              ]}
              onChange={(e) => void w.settings({ contextOverflowPolicy: e.currentTarget.value as "REJECT" | "DROP_OLDEST" })}
            />
            <div className="history-counts">
              {mode.id !== "controlled" && (
                <span>
                  Простой агент <b>{s.history.unrestricted}</b>
                </span>
              )}
              {mode.id !== "unrestricted" && (
                <span>
                  С ограничениями <b>{s.history.controlled}</b>
                </span>
              )}
            </div>
            <Tooltip label="Удаляет сохранённый контекст диалоговых режимов. Ответы останутся на экране.">
              <Button
                fullWidth
                variant="subtle"
                color="gray"
                size="xs"
                leftSection={<IconTrash size={14} />}
                disabled={locked}
                onClick={() => void w.clear("history")}
              >
                Очистить историю
              </Button>
            </Tooltip>
          </section>
        </>
      )}
      <Divider />
      <section>
        <div className="section-label">
          <IconFlask size={16} /> Готовые эксперименты
        </div>
        <Button
          justify="space-between"
          fullWidth
          variant="default"
          size="sm"
          rightSection={<IconArrowRight size={15} />}
          disabled={locked}
          onClick={() => void w.start("", "reasoning")}
        >
          Демо: 4 способа рассуждения
        </Button>
        <Button
          justify="space-between"
          fullWidth
          variant="default"
          size="sm"
          rightSection={<IconArrowRight size={15} />}
          disabled={locked}
          onClick={() => void w.start("", "temperature")}
        >
          Демо: температура
        </Button>
        <Button justify="space-between" fullWidth variant="default" size="sm" rightSection={<IconArrowRight size={15} />} disabled={locked} onClick={() => void w.start("", "tokens-short")}>
          Токены: короткий диалог
        </Button>
        <Button justify="space-between" fullWidth variant="default" size="sm" rightSection={<IconArrowRight size={15} />} disabled={locked} onClick={() => void w.start("", "tokens-long")}>
          Токены: длинный диалог
        </Button>
        <Button justify="space-between" fullWidth variant="default" size="sm" rightSection={<IconArrowRight size={15} />} disabled={locked} onClick={() => void w.start("", "tokens-overflow")}>
          Токены: переполнение 6K
        </Button>
      </section>
      <div className="sidebar-footer">
        <span className="status-dot" /> Локальное рабочее пространство
      </div>
    </aside>
  );
}
