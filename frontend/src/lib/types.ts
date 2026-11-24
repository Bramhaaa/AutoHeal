export type RunMode = "LIVE" | "REPLAY";

export type IncidentSource = "PLAYGROUND" | "WEBHOOK" | "EVAL" | "REPLAY";

export type IncidentStatus =
  | "QUEUED"
  | "PREPARING"
  | "DISTILLING"
  | "PATCHING"
  | "VERIFYING"
  | "OPENING_PR"
  | "SUCCEEDED"
  | "FAILED";

export type IncidentStage =
  | "QUEUED"
  | "INCIDENT_RECEIVED"
  | "LOG_DISTILLED"
  | "AGENT_PATCHING"
  | "SANDBOX_VERIFY"
  | "PR_OPENED";

export type IncidentEventType =
  | "STAGE_UPDATE"
  | "LOG_LINE"
  | "TOOL_CALL"
  | "PATCH_PROPOSED"
  | "PATCH_REJECTED"
  | "ATTEMPT_RESULT"
  | "COMPLETED"
  | "FAILED";

export const ALL_SSE_EVENT_TYPES: readonly IncidentEventType[] = [
  "STAGE_UPDATE",
  "LOG_LINE",
  "TOOL_CALL",
  "PATCH_PROPOSED",
  "PATCH_REJECTED",
  "ATTEMPT_RESULT",
  "COMPLETED",
  "FAILED",
] as const;

export interface ScenarioResponse {
  id: string;
  title: string;
  description: string;
  language: string;
  replayAvailable: boolean;
}

export interface SimulateRequest {
  scenarioId: string;
  mode: RunMode;
}

export interface SimulateResponse {
  runId: string;
  status: IncidentStatus;
  streamUrl: string;
}

export interface IncidentAttempt {
  runId: string;
  attemptNo: number;
  outcome:
    | "VERIFIED"
    | "APPLY_ERROR"
    | "POLICY_VIOLATION"
    | "COMPILE_ERROR"
    | "TEST_FAILURE"
    | "TIMEOUT";
  diff: string | null;
  sandboxExitCode: number;
  testsRun: number;
  testsFailed: number;
  sandboxDurationMs: number;
  createdAt: string;
}

export interface IncidentRunResponse {
  runId: string;
  source: IncidentSource;
  scenarioId: string | null;
  repoName: string;
  commitSha: string;
  status: IncidentStatus;
  failureReason: string | null;
  attemptsUsed: number;
  ragEnabled: boolean;
  rootCauseSummary: string | null;
  generatedDiff: string | null;
  prUrl: string | null;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  estimatedCostUsd: number;
  durationMs: number | null;
  createdAt: string;
  completedAt: string | null;
  attempts: IncidentAttempt[];
}

export interface SseEventEnvelope {
  eventId: number;
  timestamp: string;
  stage: IncidentStage;
  type: IncidentEventType;
  payload: Record<string, unknown>;
}

export interface ApiErrorResponse {
  code: string;
  message: string;
  suggestedMode: RunMode | null;
}

export interface BenchmarkAblationRow {
  label: string;
  passAt1Rate: number;
  passAt3Rate: number;
  medianDurationMs: number;
  meanCostUsd: number;
}

export interface BenchmarkFailureBucket {
  reason: string;
  count: number;
}

export interface BenchmarkSummary {
  reportId: string;
  generatedAt: string;
  totalBugs: number;
  passAt1Rate: number;
  passAt3Rate: number;
  medianDurationMs: number;
  p90DurationMs: number;
  meanCostUsd: number;
  ablations: BenchmarkAblationRow[];
  failureTaxonomy: BenchmarkFailureBucket[];
}
