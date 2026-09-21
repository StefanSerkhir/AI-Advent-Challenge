import {useCallback, useEffect, useRef, useState} from "react";
import {api, ApiError} from "../api/client";
import type {
    AssistantInvariantCategory,
    AssistantProfileInput,
    MemoryLayer,
    Provider,
    Settings,
    StartCommand,
    State
} from "../api/types";

export function useWorkbench() {
  const [state, setState] = useState<State | null>(null);
  const [connected, setConnected] = useState(false);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uncertain, setUncertain] = useState(false);
  const locked = useRef(false);
  const pending = useRef<StartCommand | null>(null);
  const accept = useCallback(
    (next: State, reset = false) =>
      setState((previous) =>
        reset || !previous || next.revision >= previous.revision
          ? next
          : previous,
      ),
    [],
  );

  useEffect(() => {
    let disposed = false;
    let reset = true;
    let events: EventSource | null = null;
    const connect = () => {
      if (disposed || !navigator.onLine) return;
      events?.close();
      reset = true;
      events = new EventSource("/api/events");
      events.addEventListener("state", (event: MessageEvent<string>) => {
        if (disposed || !navigator.onLine) return;
        try {
          accept(JSON.parse(event.data) as State, reset);
          reset = false;
          setConnected(true);
        } catch {
          setConnected(false);
          setError(
            "Не удалось прочитать состояние сервера. Обновите страницу.",
          );
        }
      });
      events.onerror = () => {
        reset = true;
        setConnected(false);
      };
    };
    const offline = () => {
      events?.close();
      setConnected(false);
    };
    window.addEventListener("offline", offline);
    window.addEventListener("online", connect);
    connect();
    api
      .state()
      .then((next) => {
        if (!disposed) accept(next);
      })
      .catch((e) => {
        if (!disposed) setError(e.message);
      });
    return () => {
      disposed = true;
      events?.close();
      window.removeEventListener("offline", offline);
      window.removeEventListener("online", connect);
    };
  }, [accept]);

  const perform = useCallback(
    async (action: () => Promise<void>) => {
      if (locked.current) return false;
      locked.current = true;
      setWorking(true);
      setError(null);
      try {
        await action();
        return true;
      } catch (e) {
        setError(
          e instanceof Error ? e.message : "Не удалось выполнить действие.",
        );
        if (e instanceof ApiError && e.status === 409)
          await api
            .state()
            .then((next) => accept(next))
            .catch(() => {});
        return false;
      } finally {
        locked.current = false;
        setWorking(false);
      }
    },
    [accept],
  );

  const start = (prompt: string, demo: StartCommand["demo"] = null) =>
    perform(async () => {
      if (!state) return;
      pending.current ??= {
        requestId: crypto.randomUUID(),
        expectedSettingsVersion: state.settingsVersion,
        prompt,
        demo,
      };
      try {
        await api.start(pending.current);
        pending.current = null;
        setUncertain(false);
        // SSE is authoritative; refresh also covers a fast operation finishing before POST resolves.
        await api
          .state()
          .then((next) => accept(next))
          .catch(() => {});
      } catch (e) {
        if (e instanceof ApiError && e.code === "network") setUncertain(true);
        else {
          pending.current = null;
          setUncertain(false);
        }
        throw e;
      }
    });

  return {
    state,
    connected,
    working,
    error,
    uncertain,
    start,
    dismissError: () => setError(null),
    refresh: () => perform(async () => accept(await api.state(), true)),
    settings: (patch: Partial<Settings>) =>
      perform(async () => {
        if (state)
          accept(
            await api.settings(
              { ...state.settings, ...patch },
              state.settingsVersion,
            ),
          );
      }),
    saveKey: (provider: Provider, key: string) =>
      perform(async () => {
        if (state) accept(await api.key(provider, key, state.settingsVersion));
      }),
    cancel: () =>
      perform(async () => {
        if (state?.operation) accept(await api.cancel(state.operation.id));
      }),
    checkpoint: () =>
      perform(async () => {
        if (state) accept(await api.checkpoint(state.settingsVersion));
      }),
    switchBranch: (branchId: string) =>
      perform(async () => {
        if (state) accept(await api.switchBranch(branchId, state.settingsVersion));
      }),
    addMemory: (layer: MemoryLayer, text: string) =>
      perform(async () => {
        if (state) accept(await api.addMemory(layer, text, state.settingsVersion));
      }),
    updateMemory: (layer: MemoryLayer, id: string, text: string) =>
      perform(async () => {
        if (state) accept(await api.updateMemory(layer, id, text, state.settingsVersion));
      }),
    deleteMemory: (layer: MemoryLayer, id: string) =>
      perform(async () => {
        if (state) accept(await api.deleteMemory(layer, id, state.settingsVersion));
      }),
    clearMemory: (layer: MemoryLayer) =>
      perform(async () => {
        if (state) accept(await api.clearMemory(layer, state.settingsVersion));
      }),
    setMemoryEnabled: (layer: MemoryLayer, enabled: boolean) =>
      perform(async () => {
        if (state) accept(await api.setMemoryEnabled(layer, enabled, state.settingsVersion));
      }),
    newDialogue: () => perform(async () => {
      if (state) accept(await api.newDialogue(state.settingsVersion));
    }),
    completeTask: () => perform(async () => {
      if (state) accept(await api.completeTask(state.settingsVersion));
    }),
    saveProfile: (profile: AssistantProfileInput) => perform(async () => {
      if (state) accept(await api.saveProfile(profile, state.settingsVersion));
    }),
    addInvariant: (category: AssistantInvariantCategory, text: string) => perform(async () => {
      if (state) accept(await api.addInvariant(category, text, state.settingsVersion));
    }),
    updateInvariant: (id: string, category: AssistantInvariantCategory, text: string) => perform(async () => {
      if (state) accept(await api.updateInvariant(id, category, text, state.settingsVersion));
    }),
    deleteInvariant: (id: string) => perform(async () => {
      if (state) accept(await api.deleteInvariant(id, state.settingsVersion));
    }),
    startTaskState: (goal: string, currentStep: string, expectedAction: string) => perform(async () => {
      if (state) accept(await api.startTaskState(goal, currentStep, expectedAction, state.settingsVersion));
    }),
    updateTaskProgress: (currentStep: string, expectedAction: string) => perform(async () => {
      if (state) accept(await api.updateTaskProgress(currentStep, expectedAction, state.settingsVersion));
    }),
    approveTaskPlan: () => perform(async () => {
      if (state) accept(await api.approveTaskPlan(state.settingsVersion));
    }),
    completeTaskImplementation: () => perform(async () => {
      if (state) accept(await api.completeTaskImplementation(state.settingsVersion));
    }),
    recordTaskValidation: (successful: boolean, details: string, expectedAction: string | null = null) => perform(async () => {
      if (state) accept(await api.recordTaskValidation(successful, details, expectedAction, state.settingsVersion));
    }),
    pauseTaskState: () => perform(async () => {
      if (state) accept(await api.pauseTaskState(state.settingsVersion));
    }),
    resumeTaskState: () => perform(async () => {
      if (state) accept(await api.resumeTaskState(state.settingsVersion));
    }),
    resetTaskState: () => perform(async () => {
      if (state) accept(await api.resetTaskState(state.settingsVersion));
    }),
    clear: (target: "history" | "results" | "notice") =>
      perform(async () => accept(await api.clear(target))),
  };
}
export type Workbench = ReturnType<typeof useWorkbench>;
