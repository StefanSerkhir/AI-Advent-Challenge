import {Badge} from "@mantine/core";
import {IconClock} from "@tabler/icons-react";
import type {BackgroundTask, BackgroundTasksState} from "../api/types";

const dateTime = (value: string | null) => value
  ? new Intl.DateTimeFormat("ru-RU", {dateStyle: "short", timeStyle: "medium"}).format(new Date(value))
  : "—";

const status: Record<BackgroundTask["status"], {label: string; color: string}> = {
  active: {label: "активна", color: "blue"},
  completed: {label: "завершена", color: "teal"},
  failed: {label: "ошибка", color: "red"},
  cancelled: {label: "отменена", color: "gray"},
};

export function BackgroundTasks({state}: {state: BackgroundTasksState}) {
  return <section className="background-tasks" data-testid="background-tasks">
    <div className="background-tasks-heading">
      <div><IconClock size={17}/><strong>Фоновые задачи</strong></div>
      <Badge size="xs" variant="light" color={state.available ? "indigo" : "orange"}>
        {state.available ? state.schedules.length : "переподключение"}
      </Badge>
    </div>
    {state.error && <p className="background-tasks-empty">{state.error}</p>}
    {state.available && state.schedules.length === 0 &&
      <p className="background-tasks-empty">Расписаний пока нет. Создайте их разговором с Простым агентом.</p>}
    {state.schedules.length > 0 && <div className="background-task-list">
      {state.schedules.map((task) => <article key={task.id} className="background-task" data-testid="background-task">
        <div className="background-task-title">
          <strong>{task.title}</strong>
          <Badge size="xs" variant="light" color={status[task.status].color}>{status[task.status].label}</Badge>
          <Badge size="xs" variant="outline" color="gray">
            {task.scheduleType === "once" ? "однократно" : "периодически"}
          </Badge>
        </div>
        <div className="background-task-times">
          <span>Последнее: <b>{dateTime(task.lastRunAt)}</b></span>
          <span>Следующее: <b>{dateTime(task.nextRunAt)}</b></span>
          <span>Успешно / ошибок: <b>{task.successfulRuns} / {task.failedRuns}</b></span>
        </div>
        <p>{task.summary}</p>
        <code title={task.id}>{task.id}</code>
      </article>)}
    </div>}
  </section>;
}
