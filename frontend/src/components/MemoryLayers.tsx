import {useEffect, useState} from "react";
import {ActionIcon, Badge, Button, Group, NativeSelect, Switch, Textarea, TextInput, Tooltip} from "@mantine/core";
import {IconEdit, IconMessagePlus, IconRefresh, IconTrash} from "@tabler/icons-react";
import type {AssistantProfileInput, MemoryLayer} from "../api/types";
import type {Workbench} from "../state/useWorkbench";
import {TaskStatePanel} from "./TaskStatePanel";
import {AssistantInvariants} from "./AssistantInvariants";

const labels: Record<MemoryLayer, string> = {
  SHORT_TERM: "Краткосрочная",
  WORKING: "Рабочая",
  LONG_TERM: "Долговременная",
};

export function MemoryLayers({workbench: w, locked}: {workbench: Workbench; locked: boolean}) {
  const state = w.state!;
  const [layer, setLayer] = useState<MemoryLayer>("WORKING");
  const [text, setText] = useState("");
  const add = async () => {
    if (text.trim() && await w.addMemory(layer, text)) setText("");
  };
  return <div className="memory-layers" data-testid="memory-layers">
    <p className="micro">Все три слоя работают одновременно. Краткосрочный слой пополняется только завершёнными парами диалога.</p>
    <AssistantInvariants workbench={w} locked={locked}/>
    <TaskStatePanel workbench={w} locked={locked}/>
    <ProfileEditor workbench={w} locked={locked}/>
    {state.assistantMemory.layers.map((memory) => <section className="memory-layer" data-layer={memory.layer} key={memory.layer}>
      <div className="memory-layer-heading">
        <strong>{labels[memory.layer]}</strong>
        <Badge size="xs" variant="light">{memory.count}</Badge>
      </div>
      <Switch
        size="xs"
        label="Учитывать в ответе"
        checked={memory.enabled}
        disabled={locked}
        onChange={(event) => void w.setMemoryEnabled(memory.layer, event.currentTarget.checked)}
      />
      <div className="memory-entries">
        {memory.entries.length === 0 && <span className="micro">Пока пусто</span>}
        {memory.entries.map((entry) => <div className="memory-entry" key={entry.id} data-entry-id={entry.id}>
          <span>{entry.role === "USER" ? "Вы" : entry.role === "ASSISTANT" ? "Агент" : "Запись"}: {entry.text}</span>
          <Group gap={2} wrap="nowrap">
            <Tooltip label="Редактировать">
              <ActionIcon size="xs" variant="subtle" aria-label={`Редактировать ${entry.id}`} disabled={locked}
                onClick={() => {
                  const next = window.prompt("Новый текст записи", entry.text);
                  if (next !== null && next.trim() && next.trim() !== entry.text) void w.updateMemory(memory.layer, entry.id, next);
                }}><IconEdit size={13}/></ActionIcon>
            </Tooltip>
            <Tooltip label={memory.layer === "SHORT_TERM" ? "Удалить всю пару" : "Удалить запись"}>
              <ActionIcon size="xs" color="red" variant="subtle" aria-label={`Удалить ${entry.id}`} disabled={locked}
                onClick={() => void w.deleteMemory(memory.layer, entry.id)}><IconTrash size={13}/></ActionIcon>
            </Tooltip>
          </Group>
        </div>)}
      </div>
      <Button size="compact-xs" variant="subtle" color="gray" leftSection={<IconTrash size={12}/>} disabled={locked || memory.count === 0}
        onClick={() => {
          if (window.confirm(`Очистить слой «${labels[memory.layer]}»? Это не затронет остальные слои.`)) void w.clearMemory(memory.layer);
        }}>Очистить слой</Button>
    </section>)}
    <div className="memory-add">
      <NativeSelect label="Добавить в слой" value={layer} disabled={locked}
        data={[{value: "WORKING", label: labels.WORKING}, {value: "LONG_TERM", label: labels.LONG_TERM}]}
        onChange={(event) => setLayer(event.currentTarget.value as MemoryLayer)}/>
      <Textarea label="Текст записи" value={text} maxLength={16384} disabled={locked}
        onChange={(event) => setText(event.currentTarget.value)} minRows={2}/>
      <Button size="xs" leftSection={<IconMessagePlus size={14}/>} disabled={locked || !text.trim()} onClick={() => void add()}>Добавить запись</Button>
    </div>
    <Group grow>
      <Button size="xs" variant="light" leftSection={<IconRefresh size={14}/>} disabled={locked}
        onClick={() => {
          if (window.confirm("Начать новый диалог? Краткосрочная память будет очищена.")) void w.newDialogue();
        }}>Новый диалог</Button>
      <Button size="xs" variant="light" color="orange" disabled={locked}
        onClick={() => {
          if (window.confirm("Завершить задачу? Рабочая память будет очищена.")) void w.completeTask();
        }}>Завершить задачу</Button>
    </Group>
  </div>;
}

function ProfileEditor({workbench: w, locked}: {workbench: Workbench; locked: boolean}) {
  const profile = w.state!.assistantProfile;
  const [draft, setDraft] = useState<AssistantProfileInput>(() => ({
    preferredName: profile.preferredName,
    about: profile.about,
    responseStyle: profile.responseStyle,
    responseFormat: profile.responseFormat,
    constraints: profile.constraints,
  }));
  useEffect(() => setDraft({
    preferredName: profile.preferredName,
    about: profile.about,
    responseStyle: profile.responseStyle,
    responseFormat: profile.responseFormat,
    constraints: profile.constraints,
  }), [profile]);
  const changed = (Object.keys(draft) as (keyof AssistantProfileInput)[])
    .some((key) => draft[key] !== profile[key]);
  const patch = (field: keyof AssistantProfileInput, value: string) =>
    setDraft((current) => ({...current, [field]: value}));
  return <section className="assistant-profile" data-testid="assistant-profile">
    <div className="memory-layer-heading">
      <strong>Профиль пользователя</strong>
      <Badge size="xs" variant="light">v{profile.version} · {profile.configuredFieldCount}/5</Badge>
    </div>
    <p className="micro">Применяется автоматически. Явные требования текущего запроса временно важнее сохранённых предпочтений.</p>
    <TextInput label="Имя или обращение" value={draft.preferredName} maxLength={120} disabled={locked}
      onChange={(event) => patch("preferredName", event.currentTarget.value)}/>
    <Textarea label="О вас и вашем контексте" value={draft.about} maxLength={4000} disabled={locked} minRows={2}
      onChange={(event) => patch("about", event.currentTarget.value)}/>
    <Textarea label="Предпочтительный стиль ответа" value={draft.responseStyle} maxLength={1000} disabled={locked} minRows={2}
      onChange={(event) => patch("responseStyle", event.currentTarget.value)}/>
    <Textarea label="Предпочтительный формат ответа" value={draft.responseFormat} maxLength={1000} disabled={locked} minRows={2}
      onChange={(event) => patch("responseFormat", event.currentTarget.value)}/>
    <Textarea label="Ограничения и дополнительные пожелания" value={draft.constraints} maxLength={4000} disabled={locked} minRows={2}
      onChange={(event) => patch("constraints", event.currentTarget.value)}/>
    <Button size="xs" disabled={locked || !changed} onClick={() => void w.saveProfile(draft)}>Сохранить профиль</Button>
  </section>;
}
