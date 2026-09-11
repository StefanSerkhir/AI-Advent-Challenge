import type {Provider, Settings, StartCommand, State} from "./types";

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
  clear: (target: "history" | "results" | "notice") =>
    request<State>(`/${target}`, "DELETE"),
};
