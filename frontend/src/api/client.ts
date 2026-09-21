import type {
    AssistantInvariantCategory,
    AssistantProfileInput,
    MemoryLayer,
    Provider,
    Settings,
    StartCommand,
    State
} from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly status = 0,
  ) {
    super(message);
  }
}
async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method,
      cache: "no-store",
      headers:
        method === "GET"
          ? {}
          : { "Content-Type": "application/json", "X-Workbench-Request": "1" },
      body: method === "GET" ? undefined : JSON.stringify(body ?? {}),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    throw new ApiError(
      "Нет связи с сервером. Проверьте, что он запущен. Выполняющийся эксперимент продолжится на сервере.",
      "network",
    );
  }
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new ApiError(
      error?.message ?? "Сервер не смог выполнить команду.",
      error?.code ?? "server",
      response.status,
    );
  }
  return response.json() as Promise<T>;
}
export const api = {
  state: () => request<State>("/state"),
  settings: (settings: Settings, expectedSettingsVersion: number) =>
    request<State>("/settings", "PUT", { settings, expectedSettingsVersion }),
  key: (provider: Provider, key: string, expectedSettingsVersion: number) =>
    request<State>("/key", "PUT", { provider, key, expectedSettingsVersion }),
  start: (command: StartCommand) =>
    request<{ operationId: number }>("/operations", "POST", command),
  cancel: (id: number) => request<State>(`/operations/${id}/cancel`, "POST"),
  checkpoint: (expectedSettingsVersion: number) =>
    request<State>("/context/checkpoint", "POST", { expectedSettingsVersion }),
  switchBranch: (branchId: string, expectedSettingsVersion: number) =>
    request<State>("/context/branch", "POST", { branchId, expectedSettingsVersion }),
  addMemory: (layer: MemoryLayer, text: string, expectedSettingsVersion: number) =>
    request<State>("/assistant/memory", "POST", { layer, text, expectedSettingsVersion }),
  updateMemory: (layer: MemoryLayer, id: string, text: string, expectedSettingsVersion: number) =>
    request<State>("/assistant/memory", "PUT", { layer, id, text, expectedSettingsVersion }),
  deleteMemory: (layer: MemoryLayer, id: string, expectedSettingsVersion: number) =>
    request<State>("/assistant/memory", "DELETE", { layer, id, expectedSettingsVersion }),
  clearMemory: (layer: MemoryLayer, expectedSettingsVersion: number) =>
    request<State>("/assistant/memory/clear", "POST", { layer, expectedSettingsVersion }),
  setMemoryEnabled: (layer: MemoryLayer, enabled: boolean, expectedSettingsVersion: number) =>
    request<State>("/assistant/memory/enabled", "PUT", { layer, enabled, expectedSettingsVersion }),
  newDialogue: (expectedSettingsVersion: number) =>
    request<State>("/assistant/dialogue/new", "POST", { expectedSettingsVersion }),
  completeTask: (expectedSettingsVersion: number) =>
    request<State>("/assistant/task/complete", "POST", { expectedSettingsVersion }),
  saveProfile: (profile: AssistantProfileInput, expectedSettingsVersion: number) =>
    request<State>("/assistant/profile", "PUT", { profile, expectedSettingsVersion }),
  addInvariant: (category: AssistantInvariantCategory, text: string, expectedSettingsVersion: number) =>
    request<State>("/assistant/invariants", "POST", { category, text, expectedSettingsVersion }),
  updateInvariant: (id: string, category: AssistantInvariantCategory, text: string, expectedSettingsVersion: number) =>
    request<State>("/assistant/invariants", "PUT", { id, category, text, expectedSettingsVersion }),
  deleteInvariant: (id: string, expectedSettingsVersion: number) =>
    request<State>("/assistant/invariants", "DELETE", { id, expectedSettingsVersion }),
  startTaskState: (goal: string, currentStep: string, expectedAction: string, expectedSettingsVersion: number) =>
    request<State>("/assistant/task-state/start", "POST", { goal, currentStep, expectedAction, expectedSettingsVersion }),
  updateTaskProgress: (currentStep: string, expectedAction: string, expectedSettingsVersion: number) =>
    request<State>("/assistant/task-state/progress", "PUT", { currentStep, expectedAction, expectedSettingsVersion }),
  approveTaskPlan: (expectedSettingsVersion: number) =>
    request<State>("/assistant/task-state/approve-plan", "POST", { expectedSettingsVersion }),
  completeTaskImplementation: (expectedSettingsVersion: number) =>
    request<State>("/assistant/task-state/complete-implementation", "POST", { expectedSettingsVersion }),
  recordTaskValidation: (successful: boolean, details: string, expectedAction: string | null, expectedSettingsVersion: number) =>
    request<State>("/assistant/task-state/validation", "POST", { successful, details, expectedAction, expectedSettingsVersion }),
  pauseTaskState: (expectedSettingsVersion: number) =>
    request<State>("/assistant/task-state/pause", "POST", { expectedSettingsVersion }),
  resumeTaskState: (expectedSettingsVersion: number) =>
    request<State>("/assistant/task-state/resume", "POST", { expectedSettingsVersion }),
  resetTaskState: (expectedSettingsVersion: number) =>
    request<State>("/assistant/task-state/reset", "POST", { expectedSettingsVersion }),
  clear: (target: "history" | "results" | "notice") =>
    request<State>(`/${target}`, "DELETE"),
};
