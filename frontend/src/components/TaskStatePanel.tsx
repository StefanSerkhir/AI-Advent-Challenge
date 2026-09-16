import {useEffect, useState} from "react";
import {Badge, Button, Group, Textarea} from "@mantine/core";
import type {AgentTaskState, TaskPhase} from "../api/types";
import type {Workbench} from "../state/useWorkbench";

const phaseLabels: Record<TaskPhase, string> = {
  PLANNING: "Планирование",
  EXECUTION: "Выполнение",
  VALIDATION: "Проверка",
  DONE: "Завершено",
};

const nextPhase: Partial<Record<TaskPhase, TaskPhase>> = {
  PLANNING: "EXECUTION",
  EXECUTION: "VALIDATION",
  VALIDATION: "DONE",
};

const emptyDraft = {goal: "", currentStep: "", expectedAction: ""};

export function TaskStatePanel({workbench: w, locked}: {workbench: Workbench; locked: boolean}) {
  const task = w.state!.taskState;
  const [creating, setCreating] = useState(task === null);

  useEffect(() => setCreating(task === null), [task?.id, task === null]);

  return <section className="task-state-panel" data-testid="task-state-panel" aria-label="Состояние задачи">
    <div className="memory-layer-heading">
      <strong>Состояние задачи</strong>
      {task && <Group gap={5} wrap="nowrap">
        <Badge size="xs" variant="light">{phaseLabels[task.phase]}</Badge>
        <Badge size="xs" variant="dot" color={task.phase === "DONE" ? "teal" : task.paused ? "orange" : "blue"}>
          {task.phase === "DONE" ? "Завершена" : task.paused ? "На паузе" : "Активна"}
        </Badge>
      </Group>}
    </div>
    {!task || creating ? <NewTaskEditor
      workbench={w}
      locked={locked}
      cancel={task ? () => setCreating(false) : undefined}
    /> : <ExistingTaskEditor workbench={w} locked={locked} task={task} beginNew={() => setCreating(true)}/>} 
  </section>;
}

function NewTaskEditor({workbench: w, locked, cancel}: {
  workbench: Workbench;
  locked: boolean;
  cancel?: () => void;
}) {
  const [draft, setDraft] = useState(emptyDraft);
  const valid = draft.goal.trim() && draft.currentStep.trim() && draft.expectedAction.trim();
  const patch = (field: keyof typeof draft, value: string) =>
    setDraft((current) => ({...current, [field]: value}));
  const start = async () => {
    if (valid && await w.startTaskState(draft.goal, draft.currentStep, draft.expectedAction)) setDraft(emptyDraft);
  };
  return <div className="task-state-editor">
    <p className="micro">Сохранённая цель позволит продолжить работу короткой командой «Продолжай».</p>
    <Textarea label="Цель задачи" value={draft.goal} maxLength={8000} minRows={2} disabled={locked}
      onChange={(event) => patch("goal", event.currentTarget.value)}/>
    <Textarea label="Текущий шаг задачи" value={draft.currentStep} maxLength={4000} minRows={2} disabled={locked}
      onChange={(event) => patch("currentStep", event.currentTarget.value)}/>
    <Textarea label="Ожидаемое следующее действие" value={draft.expectedAction} maxLength={4000} minRows={2} disabled={locked}
      onChange={(event) => patch("expectedAction", event.currentTarget.value)}/>
    <Group grow>
      {cancel && <Button size="xs" variant="default" disabled={locked} onClick={cancel}>Отмена</Button>}
      <Button size="xs" disabled={locked || !valid} onClick={() => void start()}>Начать задачу</Button>
    </Group>
  </div>;
}

function ExistingTaskEditor({workbench: w, locked, task, beginNew}: {
  workbench: Workbench;
  locked: boolean;
  task: AgentTaskState;
  beginNew: () => void;
}) {
  const [currentStep, setCurrentStep] = useState(task.currentStep);
  const [expectedAction, setExpectedAction] = useState(task.expectedAction);
  useEffect(() => {
    setCurrentStep(task.currentStep);
    setExpectedAction(task.expectedAction);
  }, [task.id, task.version, task.currentStep, task.expectedAction]);
  const changed = currentStep !== task.currentStep || expectedAction !== task.expectedAction;
  const valid = currentStep.trim() && expectedAction.trim();
  const done = task.phase === "DONE";
  const target = nextPhase[task.phase];
  return <div className="task-state-editor">
    <div className="task-state-goal">
      <span>Цель</span>
      <p>{task.goal}</p>
      <code title={task.id}>{task.id.slice(0, 12)} · v{task.version}</code>
    </div>
    <Textarea label="Текущий шаг задачи" value={currentStep} maxLength={4000} minRows={2}
      disabled={locked || done} onChange={(event) => setCurrentStep(event.currentTarget.value)}/>
    <Textarea label="Ожидаемое следующее действие" value={expectedAction} maxLength={4000} minRows={2}
      disabled={locked || done} onChange={(event) => setExpectedAction(event.currentTarget.value)}/>
    {!done && <Button size="xs" variant="light" disabled={locked || !changed || !valid}
      onClick={() => void w.updateTaskProgress(currentStep, expectedAction)}>Сохранить шаг</Button>}
    {!done && <Group grow>
      {task.paused ? <Button size="xs" color="teal" disabled={locked || changed} onClick={() => void w.resumeTaskState()}>Продолжить задачу</Button> : <>
        <Button size="xs" variant="light" color="orange" disabled={locked || changed} onClick={() => void w.pauseTaskState()}>Поставить на паузу</Button>
        <Button size="xs" disabled={locked || !target || changed} onClick={() => void w.advanceTaskState()}>
          Далее: {target ? phaseLabels[target] : "—"}
        </Button>
      </>}
    </Group>}
    {done && <Button size="xs" onClick={beginNew} disabled={locked}>Начать новую задачу</Button>}
    <Button size="compact-xs" variant="subtle" color="red" disabled={locked}
      onClick={() => {
        if (window.confirm("Сбросить сохранённое состояние задачи? Память и история не изменятся.")) void w.resetTaskState();
      }}>Сбросить состояние задачи</Button>
  </div>;
}
