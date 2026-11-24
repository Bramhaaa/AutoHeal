import { API_ROUTES } from "@/lib/routes";
import type {
  ApiErrorResponse,
  BenchmarkSummary,
  IncidentRunResponse,
  RunMode,
  ScenarioResponse,
  SimulateRequest,
  SimulateResponse,
} from "@/lib/types";

export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly suggestedMode: RunMode | null;

  constructor(status: number, code: string, message: string, suggestedMode: RunMode | null = null) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.code = code;
    this.suggestedMode = suggestedMode;
  }
}

async function parseJsonOrThrow<T>(response: Response): Promise<T> {
  if (response.ok) {
    return (await response.json()) as T;
  }

  let parsed: Partial<ApiErrorResponse> | null = null;
  try {
    parsed = (await response.json()) as Partial<ApiErrorResponse>;
  } catch {
    parsed = null;
  }

  const code = typeof parsed?.code === "string" ? parsed.code : `HTTP_${response.status}`;
  const message =
    typeof parsed?.message === "string" && parsed.message.trim().length > 0
      ? parsed.message
      : `Request failed with status ${response.status}`;
  const suggestedMode: RunMode | null =
    parsed?.suggestedMode === "REPLAY" || parsed?.suggestedMode === "LIVE"
      ? parsed.suggestedMode
      : null;

  throw new ApiClientError(response.status, code, message, suggestedMode);
}

export async function fetchScenarios(signal?: AbortSignal): Promise<ScenarioResponse[]> {
  const response = await fetch(API_ROUTES.scenarios(), {
    method: "GET",
    headers: { Accept: "application/json" },
    cache: "no-store",
    signal,
  });
  return parseJsonOrThrow<ScenarioResponse[]>(response);
}

export async function triggerSimulation(
  request: SimulateRequest,
  signal?: AbortSignal,
): Promise<SimulateResponse> {
  const response = await fetch(API_ROUTES.simulateIncident(), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(request),
    signal,
  });
  return parseJsonOrThrow<SimulateResponse>(response);
}

export async function fetchIncidentRun(
  runId: string,
  signal?: AbortSignal,
): Promise<IncidentRunResponse> {
  const response = await fetch(API_ROUTES.incidentRun(runId), {
    method: "GET",
    headers: { Accept: "application/json" },
    cache: "no-store",
    signal,
  });
  return parseJsonOrThrow<IncidentRunResponse>(response);
}

export async function fetchBenchmarkSummary(
  signal?: AbortSignal,
): Promise<BenchmarkSummary | null> {
  const response = await fetch(API_ROUTES.benchmarksSummary(), {
    method: "GET",
    headers: { Accept: "application/json" },
    cache: "no-store",
    signal,
  });
  if (response.status === 404) {
    return null;
  }
  return parseJsonOrThrow<BenchmarkSummary>(response);
}
