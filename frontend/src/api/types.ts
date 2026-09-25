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
export type ContextStrategy = "SLIDING_WINDOW" | "STICKY_FACTS" | "BRANCHING" | "MEMORY_LAYERS";
export type MemoryLayer = "SHORT_TERM" | "WORKING" | "LONG_TERM";
export type AssistantInvariantCategory = "ARCHITECTURE" | "TECH_DECISION" | "STACK" | "BUSINESS_RULE" | "OTHER";
export type TaskPhase = "PLANNING" | "EXECUTION" | "VALIDATION" | "DONE";
export type TaskValidationStatus = "NOT_RUN" | "FAILED" | "PASSED";
export type TaskAvailableAction =
  | "UPDATE_PROGRESS"
  | "APPROVE_PLAN"
  | "COMPLETE_IMPLEMENTATION"
  | "RECORD_VALIDATION_FAILURE"
  | "CONFIRM_VALIDATION_SUCCESS"
  | "PAUSE"
  | "RESUME"
  | "RESET"
  | "START_NEW";
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
  contextStrategy: ContextStrategy;
  recentMessagesLimit: number;
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
  contextStrategy: ContextStrategy | null;
  branchId: string | null;
  branchName: string | null;
  assistantMemoryDiagnostics: AssistantMemoryDiagnostics | null;
  taskStateDiagnostics: TaskStateDiagnostics | null;
  assistantInvariantDiagnostics: AssistantInvariantDiagnostics | null;
  mcpCalls: McpCallDiagnostic[];
}
export interface McpCallDiagnostic {
  serverId: string;
  toolName: string;
  arguments: string;
  status: "success" | "error";
  result: string;
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
  context: ContextState;
  assistantMemory: AssistantMemoryState;
  assistantProfile: AssistantProfile;
  assistantInvariants: AssistantInvariantState;
  taskState: AgentTaskState | null;
  backgroundTasks: BackgroundTasksState;
}
export interface BackgroundTask {
  id: string;
  title: string;
  taskType: "reminder" | "tracker_snapshot";
  status: "active" | "completed" | "failed" | "cancelled";
  scheduleType: "once" | "fixed_interval";
  aggregationPeriod: string;
  totalRuns: number;
  successfulRuns: number;
  failedRuns: number;
  lastRunAt: string | null;
  nextRunAt: string | null;
  lastResult: string | null;
  lastError: string | null;
  snapshotCount: number;
  latestTrackerStatus: string | null;
  latestTrackerNextAction: string | null;
  statusChanges: number;
  nextActionChanges: number;
  summary: string;
}
export interface BackgroundTasksState {
  available: boolean;
  error: string | null;
  schedules: BackgroundTask[];
}
export interface ContextState {
  strategy: ContextStrategy;
  recentMessagesLimit: number;
  facts: Record<string, string>;
  factUsage: { requests: number; inputTokens: number; outputTokens: number; totalTokens: number };
  checkpoint: { id: string; createdAtEpochMillis: number; messages: HistoryMessage[] } | null;
  branches: { id: string; name: string; messages: HistoryMessage[] }[];
  activeBranchId: string;
}
export interface HistoryMessage { role: "user" | "assistant"; content: string }
export interface MemoryEntry {
  id: string;
  text: string;
  role: "USER" | "ASSISTANT" | "NOTE";
  pairId: string | null;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
}
export interface MemoryLayerState {
  layer: MemoryLayer;
  enabled: boolean;
  count: number;
  entries: MemoryEntry[];
}
export interface AssistantMemoryState { layers: MemoryLayerState[] }
export interface AssistantProfileInput {
  preferredName: string;
  about: string;
  responseStyle: string;
  responseFormat: string;
  constraints: string;
}
export interface AssistantProfile extends AssistantProfileInput {
  version: number;
  configuredFieldCount: number;
}
export interface AssistantMemoryDiagnostics {
  layers: { layer: MemoryLayer; enabled: boolean; usedCount: number; usedEntryIds: string[] }[];
  profileApplied: boolean;
  profileVersion: number | null;
  profileFieldCount: number;
}
export interface AssistantInvariant {
  id: string;
  category: AssistantInvariantCategory;
  text: string;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
}
export interface AssistantInvariantState {
  version: number;
  invariants: AssistantInvariant[];
}
export interface AssistantInvariantDiagnostics {
  applied: boolean;
  stateVersion: number;
  appliedCount: number;
  appliedInvariantIds: string[];
  responseBlocked: boolean;
}
export interface AgentTaskState {
  id: string;
  version: number;
  goal: string;
  phase: TaskPhase;
  currentStep: string;
  expectedAction: string;
  paused: boolean;
  planApprovedAtEpochMillis: number | null;
  implementationCompletedAtEpochMillis: number | null;
  validationStatus: TaskValidationStatus;
  validationDetails: string | null;
  availableActions: TaskAvailableAction[];
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
}
export interface TaskStateDiagnostics {
  applied: boolean;
  taskId: string | null;
  stateVersion: number | null;
  phase: TaskPhase | null;
  responseBlocked: boolean;
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
  context: ContextState;
}
