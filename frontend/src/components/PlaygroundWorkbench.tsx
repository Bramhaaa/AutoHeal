"use client";

import { useCallback, useEffect, useState } from "react";
import { LiveTerminal } from "@/components/LiveTerminal";
import { PatchDiffViewer } from "@/components/PatchDiffViewer";
import { PipelineVisualizer } from "@/components/PipelineVisualizer";
import { RunSummaryBar } from "@/components/RunSummaryBar";
import { ScenarioSelector } from "@/components/ScenarioSelector";
import { useIncidentStream } from "@/hooks/useIncidentStream";
import {
  ApiClientError,
  fetchIncidentRun,
  fetchScenarios,
  triggerSimulation,
} from "@/lib/api";
import type { IncidentRunResponse, RunMode, ScenarioResponse } from "@/lib/types";

type EvidenceTab = "terminal" | "diff";

export function PlaygroundWorkbench() {
  const [scenarios, setScenarios] = useState<ScenarioResponse[]>([]);
  const [loadingScenarios, setLoadingScenarios] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [selectedScenarioId, setSelectedScenarioId] = useState<string | null>(null);
  const [mode, setMode] = useState<RunMode>("LIVE");

  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [activeStreamUrl, setActiveStreamUrl] = useState<string | null>(null);
  const [activeIsReplay, setActiveIsReplay] = useState(false);

  const [completedRun, setCompletedRun] = useState<IncidentRunResponse | null>(null);
  const [activeTab, setActiveTab] = useState<EvidenceTab>("terminal");
  const [submitting, setSubmitting] = useState(false);
  const [triggerError, setTriggerError] = useState<{
    message: string;
    suggestedMode: RunMode | null;
  } | null>(null);

  const loadScenarios = useCallback(async (signal?: AbortSignal) => {
    setLoadingScenarios(true);
    setLoadError(null);
    try {
      const list = await fetchScenarios(signal);
      setScenarios(list);
      setSelectedScenarioId((prev) => prev ?? list[0]?.id ?? null);
    } catch (err) {
      if (signal?.aborted) return;
      const message =
        err instanceof Error ? err.message : "Backend API is unreachable.";
      setLoadError(message);
    } finally {
      if (!signal?.aborted) {
        setLoadingScenarios(false);
      }
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void loadScenarios(controller.signal);
    return () => controller.abort();
  }, [loadScenarios]);

  const handleTerminal = useCallback((runId: string, status: "COMPLETED" | "FAILED") => {
    void fetchIncidentRun(runId)
      .then((runDetails) => {
        setCompletedRun(runDetails);
        if (status === "COMPLETED" && runDetails.generatedDiff) {
          setActiveTab("diff");
        }
      })
      .catch(() => {
        // Keep terminal state visible if final detail fetch fails transiently.
      });
  }, []);

  const stream = useIncidentStream(
    activeRunId,
    activeStreamUrl,
    activeIsReplay,
    handleTerminal,
  );

  const startRun = useCallback(
    async (runMode: RunMode) => {
      if (!selectedScenarioId) return;
      setSubmitting(true);
      setTriggerError(null);
      setCompletedRun(null);
      setActiveTab("terminal");

      try {
        const res = await triggerSimulation({
          scenarioId: selectedScenarioId,
          mode: runMode,
        });
        setActiveRunId(res.runId);
        setActiveStreamUrl(res.streamUrl);
        setActiveIsReplay(runMode === "REPLAY");
      } catch (err) {
        if (err instanceof ApiClientError) {
          setTriggerError({
            message: err.message,
            suggestedMode: err.suggestedMode,
          });
        } else {
          setTriggerError({
            message:
              err instanceof Error ? err.message : "Unable to start incident run.",
            suggestedMode: null,
          });
        }
      } finally {
        setSubmitting(false);
      }
    },
    [selectedScenarioId],
  );

  const handleSwitchToReplayAndRun = useCallback(() => {
    setMode("REPLAY");
    void startRun("REPLAY");
  }, [startRun]);

  const isStreaming =
    stream.connectionState === "connecting" ||
    stream.connectionState === "streaming" ||
    stream.connectionState === "reconnecting";

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-end justify-between gap-3">
        <div>
          <h1 className="font-display font-bold text-2xl tracking-tight text-[var(--ink-primary)]">
            Live Incident Playground
          </h1>
          <p className="text-sm text-[var(--ink-secondary)] mt-1 max-w-2xl">
            Trigger a real Java/Spring failure, stream Maven output from an isolated
            Docker sandbox, and inspect the verified patch diff before any Draft PR opens.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[340px_1fr] gap-5 items-start">
        <ScenarioSelector
          scenarios={scenarios}
          loading={loadingScenarios}
          loadError={loadError}
          selectedScenarioId={selectedScenarioId}
          mode={mode}
          isRunning={submitting || isStreaming}
          triggerError={triggerError}
          onSelectScenario={setSelectedScenarioId}
          onSelectMode={setMode}
          onTrigger={() => void startRun(mode)}
          onSwitchToReplayAndRun={handleSwitchToReplayAndRun}
          onRetryLoad={() => void loadScenarios()}
        />

        <div className="space-y-4 min-w-0">
          <PipelineVisualizer
            currentStage={stream.currentStage}
            connectionState={stream.connectionState}
            currentAttempt={stream.currentAttempt}
            queuePosition={stream.queuePosition}
            isReplay={stream.isReplay}
            reductionPercent={stream.reductionPercent}
          />

          {completedRun && <RunSummaryBar run={completedRun} />}

          <div className="space-y-2">
            <div
              role="tablist"
              aria-label="Run evidence"
              className="flex items-center border-b border-[var(--border-hairline)]"
            >
              <button
                type="button"
                role="tab"
                aria-selected={activeTab === "terminal"}
                onClick={() => setActiveTab("terminal")}
                className={`px-4 py-2 text-xs font-display font-semibold border-b-2 transition-colors cursor-pointer ${
                  activeTab === "terminal"
                    ? "border-[var(--signal-cobalt)] text-[var(--ink-primary)]"
                    : "border-transparent text-[var(--ink-secondary)] hover:text-[var(--ink-primary)]"
                }`}
              >
                Live terminal
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={activeTab === "diff"}
                onClick={() => setActiveTab("diff")}
                className={`px-4 py-2 text-xs font-display font-semibold border-b-2 transition-colors cursor-pointer flex items-center gap-2 ${
                  activeTab === "diff"
                    ? "border-[var(--signal-cobalt)] text-[var(--ink-primary)]"
                    : "border-transparent text-[var(--ink-secondary)] hover:text-[var(--ink-primary)]"
                }`}
              >
                <span>Patch diff</span>
                {completedRun?.generatedDiff && (
                  <span className="w-2 h-2 rounded-full bg-[var(--signal-emerald)]" />
                )}
              </button>
            </div>

            {activeTab === "terminal" ? (
              <LiveTerminal
                entries={stream.terminalEntries}
                isStreaming={isStreaming}
              />
            ) : (
              <PatchDiffViewer unifiedDiff={completedRun?.generatedDiff ?? null} />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
