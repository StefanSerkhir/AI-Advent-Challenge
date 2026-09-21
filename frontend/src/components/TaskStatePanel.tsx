import {useEffect, useState} from "react";
import {Alert, Badge, Button, Group, Textarea} from "@mantine/core";
import type {AgentTaskState, TaskAvailableAction, TaskPhase} from "../api/types";
import type {Workbench} from "../state/useWorkbench";

const phaseLabels: Record<TaskPhase, string> = {
  PLANNING: "Планирование",
  EXECUTION: "Выполнение",
  VALIDATION: "Проверка",
  DONE: "Завершено",
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
  const [validationDetails, setValidationDetails] = useState("");
  useEffect(() => {
    setCurrentStep(task.currentStep);
    setExpectedAction(task.expectedAction);
    setValidationDetails("");
  }, [task.id, task.version, task.currentStep, task.expectedAction]);
  const can = (action: TaskAvailableAction) => task.availableActions.includes(action);
  const stepChanged = currentStep !== task.currentStep;
  const actionChanged = expectedAction !== task.expectedAction;
  const changed = stepChanged || actionChanged;
  const valid = !!currentStep.trim() && !!expectedAction.trim();
  const validationReady = !!validationDetails.trim();
  const editingEnabled = can("UPDATE_PROGRESS") && !locked;

  return <div className="task-state-editor">
    <div className="task-state-goal">
      <span>Цель</span>
      <p>{task.goal}</p>
      <code title={task.id}>{task.id.slice(0, 12)} · v{task.version}</code>
    </div>
    <Textarea label="Текущий шаг задачи" value={currentStep} maxLength={4000} minRows={2}
      disabled={!editingEnabled} onChange={(event) => setCurrentStep(event.currentTarget.value)}/>
    <Textarea label="Ожидаемое следующее действие" value={expectedAction} maxLength={4000} minRows={2}
      disabled={!editingEnabled} onChange={(event) => setExpectedAction(event.currentTarget.value)}/>
    {can("UPDATE_PROGRESS") && <Button size="xs" variant="light" disabled={locked || !changed || !valid}
      onClick={() => void w.updateTaskProgress(currentStep, expectedAction)}>Сохранить шаг</Button>}

    {task.validationStatus === "FAILED" && task.validationDetails && <Alert color="red" title="Последняя проверка не пройдена">
      {task.validationDetails}
    </Alert>}
    {(can("RECORD_VALIDATION_FAILURE") || can("CONFIRM_VALIDATION_SUCCESS")) && <>
      <Textarea label="Результат проверки" value={validationDetails} maxLength={4000} minRows={2} disabled={locked}
        placeholder="Укажите выполненные проверки и их результат"
        onChange={(event) => setValidationDetails(event.currentTarget.value)}/>
      <Group grow>
        {can("RECORD_VALIDATION_FAILURE") && <Button size="xs" variant="light" color="red"
          disabled={locked || stepChanged || !expectedAction.trim() || !validationReady}
          onClick={() => void w.recordTaskValidation(false, validationDetails, expectedAction)}>
          Проверка не пройдена
        </Button>}
        {can("CONFIRM_VALIDATION_SUCCESS") && <Button size="xs" color="teal"
          disabled={locked || changed || !validationReady}
          onClick={() => void w.recordTaskValidation(true, validationDetails)}>
          Подтвердить успешную проверку и завершить
        </Button>}
      </Group>
    </>}

    {can("APPROVE_PLAN") && <Button size="xs" disabled={locked || changed}
      onClick={() => void w.approveTaskPlan()}>Утвердить план и начать выполнение</Button>}
    {can("COMPLETE_IMPLEMENTATION") && <Button size="xs" disabled={locked || changed}
      onClick={() => void w.completeTaskImplementation()}>Передать на проверку</Button>}
    {can("PAUSE") && <Button size="xs" variant="light" color="orange" disabled={locked || changed}
      onClick={() => void w.pauseTaskState()}>Поставить на паузу</Button>}
    {can("RESUME") && <Button size="xs" color="teal" disabled={locked}
      onClick={() => void w.resumeTaskState()}>Продолжить задачу</Button>}
    {can("START_NEW") && <Button size="xs" onClick={beginNew} disabled={locked}>Начать новую задачу</Button>}
    {can("RESET") && <Button size="compact-xs" variant="subtle" color="red" disabled={locked}
      onClick={() => {
        if (window.confirm("Сбросить сохранённое состояние задачи? Память и история не изменятся.")) void w.resetTaskState();
      }}>Сбросить состояние задачи</Button>}
  </div>;
}
