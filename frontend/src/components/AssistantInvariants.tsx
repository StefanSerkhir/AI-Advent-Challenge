import {useState} from "react";
import {ActionIcon, Badge, Button, Group, NativeSelect, Textarea, Tooltip} from "@mantine/core";
import {IconEdit, IconShieldCheck, IconTrash} from "@tabler/icons-react";
import type {AssistantInvariantCategory} from "../api/types";
import type {Workbench} from "../state/useWorkbench";

const categoryLabels: Record<AssistantInvariantCategory, string> = {
  ARCHITECTURE: "Архитектура",
  TECH_DECISION: "Техническое решение",
  STACK: "Стек",
  BUSINESS_RULE: "Бизнес-правило",
  OTHER: "Другое",
};

const categoryOptions = Object.entries(categoryLabels).map(([value, label]) => ({value, label}));

export function AssistantInvariants({workbench: w, locked}: {workbench: Workbench; locked: boolean}) {
  const state = w.state!.assistantInvariants;
  const [category, setCategory] = useState<AssistantInvariantCategory>("ARCHITECTURE");
  const [text, setText] = useState("");
  const add = async () => {
    if (text.trim() && await w.addInvariant(category, text)) setText("");
  };

  return <section className="assistant-invariants" data-testid="assistant-invariants">
    <div className="memory-layer-heading">
      <strong><IconShieldCheck size={14}/> Инварианты ассистента</strong>
      <Badge size="xs" variant="light" color="violet">v{state.version} · {state.invariants.length}</Badge>
    </div>
    <p className="micro">Обязательные правила с приоритетом над запросом, состоянием задачи, профилем и памятью. Запрос не может их переопределить.</p>
    <div className="invariant-list">
      {state.invariants.length === 0 && <span className="micro">Пока нет инвариантов</span>}
      {state.invariants.map((invariant) => <div className="invariant-entry" key={invariant.id} data-invariant-id={invariant.id}>
        <NativeSelect
          aria-label={`Категория ${invariant.id}`}
          value={invariant.category}
          data={categoryOptions}
          disabled={locked}
          onChange={(event) => void w.updateInvariant(
            invariant.id,
            event.currentTarget.value as AssistantInvariantCategory,
            invariant.text,
          )}
        />
        <span>{invariant.text}</span>
        <Group gap={2} wrap="nowrap">
          <Tooltip label="Редактировать правило">
            <ActionIcon size="xs" variant="subtle" aria-label={`Редактировать инвариант ${invariant.id}`} disabled={locked}
              onClick={() => {
                const next = window.prompt("Новый текст инварианта", invariant.text);
                if (next !== null && next.trim() && next.trim() !== invariant.text) {
                  void w.updateInvariant(invariant.id, invariant.category, next);
                }
              }}><IconEdit size={13}/></ActionIcon>
          </Tooltip>
          <Tooltip label="Удалить инвариант">
            <ActionIcon size="xs" color="red" variant="subtle" aria-label={`Удалить инвариант ${invariant.id}`} disabled={locked}
              onClick={() => void w.deleteInvariant(invariant.id)}><IconTrash size={13}/></ActionIcon>
          </Tooltip>
        </Group>
      </div>)}
    </div>
    <NativeSelect label="Категория инварианта" aria-label="Категория инварианта" value={category} data={categoryOptions}
      disabled={locked} onChange={(event) => setCategory(event.currentTarget.value as AssistantInvariantCategory)}/>
    <Textarea label="Обязательное правило" aria-label="Обязательное правило" value={text} maxLength={16384}
      disabled={locked} minRows={2} onChange={(event) => setText(event.currentTarget.value)}/>
    <Button size="xs" color="violet" disabled={locked || !text.trim()} onClick={() => void add()}>Добавить инвариант</Button>
  </section>;
}
