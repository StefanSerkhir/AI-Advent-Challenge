/** Wire contract: src/main/kotlin/web/ApiDtos.kt. No credentials in server state. */
export type Mode =
  | "compare"
  | "controlled"
  | "unrestricted"
  | "reasoning"
  | "temperature"
  | "models";
export type Provider = "DEEPSEEK" | "OPENAI";
export interface Settings {
  provider: Provider;
  model: string;
  mode: Mode;
  maxTokens: number;
  maxWords: number;
  bulletCount: number;
  stopSequence: string | null;
  historyEnabled: boolean;
}
export interface ProviderInfo {
  id: Provider;
  title: string;
  hasKey: boolean;
  models: { id: string; title: string }[];
}
export interface ModeInfo {
  id: Mode;
  title: string;
  description: string;
  independentContext: boolean;
  connectionLocked: boolean;
  usesTokenLimit: boolean;
  usesTextConstraints: boolean;
  usesHistory: boolean;
}
export interface Metrics {
  characters: number | null;
  words: number | null;
  completionTokens: number | null;
  finishReason: string | null;
  promptTokens: number | null;
  reasoningTokens: number | null;
  totalTokens: number | null;
  elapsedMillis: number | null;
  estimatedCostUsd: number | null;
}
export interface Output {
  id: string;
  title: string;
  kind: "response" | "prompt" | "evaluation";
  content: string | null;
  error: string | null;
  model: string | null;
  metrics: Metrics;
  streaming: boolean;
}
export interface Exchange {
  id: number;
  prompt: string;
  mode: Mode;
  status: "pending" | "completed" | "cancelled" | "failed";
  error: string | null;
  outputs: Output[];
  estimatedTotalCostUsd: number | null;
  evaluationNote: string | null;
}
export interface Operation {
  id: number;
  progress: { current: number; total: number; label: string };
}
export interface State {
  revision: number;
  settingsVersion: number;
  settings: Settings;
  providers: ProviderInfo[];
  modes: ModeInfo[];
  history: { unrestricted: number; controlled: number };
  exchanges: Exchange[];
  operation: Operation | null;
  notice: { kind: "info" | "error"; message: string } | null;
  priceDate: string;
}
export interface StartCommand {
  requestId: string;
  expectedSettingsVersion: number;
  prompt: string;
  demo: "reasoning" | "temperature" | null;
}

/** GET /api/history exposes the immutable branch snapshot for local API consumers. */
export interface HistoryDetails {
  counts: State["history"];
  branches: Record<"unrestricted" | "controlled", { role: "user" | "assistant"; content: string }[]>;
}
