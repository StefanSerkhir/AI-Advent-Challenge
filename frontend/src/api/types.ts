/** Wire contract: src/main/kotlin/web/ApiDtos.kt. No credentials in server state. */
export type Mode =
  | "compare"
  | "controlled"
  | "unrestricted"
  | "reasoning"
  | "temperature"
  | "models"
  | "tokens";
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
  contextOverflowPolicy: "REJECT" | "DROP_OLDEST";
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
  cachedInputTokens: number | null;
  cacheWriteInputTokens: number | null;
}
export interface TokenValue {
  value: number | null;
  source: string;
  approximate: boolean;
}
export interface DecimalValue {
  value: string | null;
  source: string;
  approximate: boolean;
}
export interface ConversationTokenTotals {
  completedTurns: TokenValue;
  currentHistoryTokens: TokenValue;
  cumulativeApiInputTokens: TokenValue;
  cumulativeOutputTokens: TokenValue;
  cumulativeReasoningTokens: TokenValue;
  cumulativeTotalTokens: TokenValue;
  cumulativeCachedInputTokens: TokenValue;
  cumulativeCacheWriteInputTokens: TokenValue;
  cumulativeCostUsd: DecimalValue;
  contextTruncations: TokenValue;
  scope: "saved_conversation" | "runtime_without_history" | string;
}
export interface TurnTokenMetrics {
  id: string;
  turnNumber: number;
  model: string;
  estimatedCurrentMessageTokens: TokenValue;
  estimatedHistoryTokens: TokenValue;
  estimatedContextTokens: TokenValue;
  actualInputTokens: TokenValue;
  actualOutputTokens: TokenValue;
  reasoningTokens: TokenValue;
  cachedInputTokens: TokenValue;
  cacheWriteInputTokens: TokenValue;
  totalTokens: TokenValue;
  contextWindow: TokenValue;
  requestedMaxOutputTokens: TokenValue;
  reservedOutputTokens: TokenValue;
  availableInputTokens: TokenValue;
  contextUsagePercent: DecimalValue;
  estimatedRemainingInputTokens: TokenValue;
  finishReason: string | null;
  turnCostUsd: DecimalValue;
  cumulativeTotals: ConversationTokenTotals;
  overflowPolicy: "REJECT" | "DROP_OLDEST";
  excludedMessageCount: TokenValue;
  excludedEstimatedTokens: TokenValue;
  requiredTokens: TokenValue;
  exceededByTokens: TokenValue;
  tokenizerId: string;
  tokenizerWarning: string | null;
  pricingProfileId: string | null;
  pricingEffectiveDate: string | null;
  pricingSourceUrl: string | null;
  contextProfileSimulated: boolean;
  createdAtEpochMillis: number;
}
export interface TokenConversation {
  turns: TurnTokenMetrics[];
  totals: ConversationTokenTotals;
}
export interface Output {
  id: string;
  title: string;
  kind: "response" | "prompt" | "evaluation" | "token-turn";
  content: string | null;
  error: string | null;
  model: string | null;
  metrics: Metrics;
  streaming: boolean;
  tokenMetrics: TurnTokenMetrics | null;
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
  errorCode: string | null;
  sentinelInPermanentHistory: boolean | null;
  sentinelInActiveContext: boolean | null;
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
  tokenConversations: Record<string, TokenConversation>;
}
export interface StartCommand {
  requestId: string;
  expectedSettingsVersion: number;
  prompt: string;
  demo:
    | "reasoning"
    | "temperature"
    | "tokens-short"
    | "tokens-long"
    | "tokens-overflow"
    | null;
}

/** GET /api/history exposes the immutable branch snapshot for local API consumers. */
export interface HistoryDetails {
  counts: State["history"];
  branches: Record<"unrestricted" | "controlled", { role: "user" | "assistant"; content: string }[]>;
  tokenConversations: Record<string, TokenConversation>;
}
