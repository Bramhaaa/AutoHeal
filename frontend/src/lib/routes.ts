const DEFAULT_API_BASE_URL = "http://localhost:8080";

export function getApiBaseUrl(): string {
  const configured = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (!configured || configured.trim().length === 0) {
    return DEFAULT_API_BASE_URL;
  }
  return configured.replace(/\/+$/, "");
}

export const API_ROUTES = {
  scenarios: () => `${getApiBaseUrl()}/api/v1/scenarios`,
  simulateIncident: () => `${getApiBaseUrl()}/api/v1/incidents/simulate`,
  incidentRun: (runId: string) =>
    `${getApiBaseUrl()}/api/v1/incidents/${encodeURIComponent(runId)}`,
  incidentStream: (runId: string) =>
    `${getApiBaseUrl()}/api/v1/incidents/${encodeURIComponent(runId)}/stream`,
  resolveStreamUrl: (streamUrl: string) => {
    if (streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) {
      return streamUrl;
    }
    const normalizedPath = streamUrl.startsWith("/") ? streamUrl : `/${streamUrl}`;
    return `${getApiBaseUrl()}${normalizedPath}`;
  },
  benchmarksSummary: () => `${getApiBaseUrl()}/api/v1/benchmarks/summary`,
} as const;
