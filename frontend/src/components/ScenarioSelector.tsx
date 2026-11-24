"use client";

import type { RunMode, ScenarioResponse } from "@/lib/types";

interface ScenarioSelectorProps {
  scenarios: ScenarioResponse[];
  loading: boolean;
  loadError: string | null;
  selectedScenarioId: string | null;
  mode: RunMode;
  isRunning: boolean;
  triggerError: {
    message: string;
    suggestedMode: RunMode | null;
  } | null;
  onSelectScenario: (scenarioId: string) => void;
  onSelectMode: (mode: RunMode) => void;
  onTrigger: () => void;
  onSwitchToReplayAndRun: () => void;
  onRetryLoad: () => void;
}

export function ScenarioSelector({
  scenarios,
  loading,
  loadError,
  selectedScenarioId,
  mode,
  isRunning,
  triggerError,
  onSelectScenario,
  onSelectMode,
  onTrigger,
  onSwitchToReplayAndRun,
  onRetryLoad,
}: ScenarioSelectorProps) {
  const selectedScenario =
    scenarios.find((s) => s.id === selectedScenarioId) ?? null;
  const replayDisabled =
    mode === "REPLAY" &&
    selectedScenario !== null &&
    !selectedScenario.replayAvailable;

  return (
    <aside className="bg-[var(--surface-panel)] border border-[var(--border-hairline)] flex flex-col">
      <div className="px-4 py-3.5 border-b border-[var(--border-hairline)]">
        <h2 className="font-display font-semibold text-base text-[var(--ink-primary)]">
          Bug scenarios
        </h2>
        <p className="text-xs text-[var(--ink-secondary)] mt-0.5">
          Deterministic Java 21 failures verified against their original unit tests.
        </p>
      </div>

      <div className="p-3 flex-1">
        {loading ? (
          <div className="space-y-2" aria-busy="true" aria-label="Loading scenarios">
            {[0, 1, 2, 3].map((idx) => (
              <div
                key={idx}
                className="h-20 border border-[var(--border-hairline)] bg-[var(--surface-canvas)] animate-pulse"
              />
            ))}
          </div>
        ) : loadError ? (
          <div className="p-3 border border-[var(--signal-crimson)] bg-red-50 text-sm">
            <p className="font-medium text-[var(--signal-crimson)]">
              Could not load scenarios
            </p>
            <p className="text-xs text-[var(--ink-secondary)] mt-1">{loadError}</p>
            <button
              type="button"
              onClick={onRetryLoad}
              className="mt-2.5 px-2.5 py-1 text-xs font-medium border border-[var(--border-hairline)] bg-white text-[var(--ink-primary)] hover:bg-[var(--surface-muted)] cursor-pointer"
            >
              Retry connection
            </button>
          </div>
        ) : (
          <div role="radiogroup" aria-label="Incident scenarios" className="space-y-2">
            {scenarios.map((scenario) => {
              const selected = scenario.id === selectedScenarioId;
              return (
                <button
                  key={scenario.id}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  onClick={() => onSelectScenario(scenario.id)}
                  className={`w-full text-left p-3 border transition-colors cursor-pointer focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--signal-cobalt)] ${
                    selected
                      ? "border-[var(--signal-cobalt)] bg-blue-50/50"
                      : "border-[var(--border-hairline)] bg-[var(--surface-panel)] hover:bg-[var(--surface-canvas)]"
                  }`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <span className="font-display font-semibold text-sm text-[var(--ink-primary)] leading-snug">
                      {scenario.title}
                    </span>
                    <span className="shrink-0 font-mono text-[11px] px-1.5 py-0.5 bg-[var(--surface-muted)] text-[var(--ink-secondary)]">
                      {scenario.language}
                    </span>
                  </div>
                  <p className="text-xs text-[var(--ink-secondary)] mt-1.5 leading-relaxed">
                    {scenario.description}
                  </p>
                  <div className="mt-2 flex items-center justify-between text-[11px] font-mono text-[var(--ink-secondary)]">
                    <span>{scenario.id}</span>
                    {scenario.replayAvailable ? (
                      <span className="text-[var(--signal-emerald)]">Replay ready</span>
                    ) : (
                      <span>Live only</span>
                    )}
                  </div>
                </button>
              );
            })}
          </div>
        )}
      </div>

      <div className="p-4 border-t border-[var(--border-hairline)] bg-[var(--surface-canvas)] space-y-3">
        <div>
          <span className="block text-xs font-medium text-[var(--ink-secondary)] mb-1.5">
            Execution mode
          </span>
          <div
            role="group"
            aria-label="Execution mode"
            className="grid grid-cols-2 border border-[var(--border-hairline)] bg-white"
          >
            <button
              type="button"
              onClick={() => onSelectMode("LIVE")}
              aria-pressed={mode === "LIVE"}
              className={`py-2 px-3 text-xs font-medium transition-colors cursor-pointer ${
                mode === "LIVE"
                  ? "bg-[var(--ink-primary)] text-white"
                  : "text-[var(--ink-secondary)] hover:text-[var(--ink-primary)]"
              }`}
            >
              Live run
            </button>
            <button
              type="button"
              onClick={() => onSelectMode("REPLAY")}
              aria-pressed={mode === "REPLAY"}
              className={`py-2 px-3 text-xs font-medium border-l border-[var(--border-hairline)] transition-colors cursor-pointer ${
                mode === "REPLAY"
                  ? "bg-[var(--ink-primary)] text-white"
                  : "text-[var(--ink-secondary)] hover:text-[var(--ink-primary)]"
              }`}
            >
              Instant replay
            </button>
          </div>
          <p className="text-[11px] text-[var(--ink-secondary)] mt-1.5">
            {mode === "LIVE"
              ? "Runs real JGit clone, Gemini triage, and Docker Maven verification."
              : "Streams a previously recorded verified run with zero LLM cost."}
          </p>
        </div>

        {triggerError && (
          <div
            role="alert"
            className="p-2.5 border border-[var(--signal-amber)] bg-amber-50 text-xs space-y-2"
          >
            <p className="text-[var(--ink-primary)]">{triggerError.message}</p>
            {triggerError.suggestedMode === "REPLAY" && (
              <button
                type="button"
                onClick={onSwitchToReplayAndRun}
                className="px-2.5 py-1 bg-[var(--ink-primary)] text-white font-medium hover:opacity-90 cursor-pointer"
              >
                Switch to Instant replay
              </button>
            )}
          </div>
        )}

        <button
          type="button"
          disabled={isRunning || !selectedScenario || replayDisabled}
          onClick={onTrigger}
          className="w-full py-2.5 px-4 font-display font-semibold text-sm text-white bg-[var(--signal-cobalt)] hover:bg-blue-800 disabled:opacity-50 disabled:cursor-not-allowed transition-colors cursor-pointer focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--signal-cobalt)]"
        >
          {isRunning
            ? mode === "REPLAY"
              ? "Streaming replay..."
              : "Healing in progress..."
            : mode === "REPLAY"
              ? "Start replay"
              : "Simulate crash"}
        </button>

        {replayDisabled && (
          <p className="text-[11px] text-[var(--signal-amber)]">
            No recorded replay exists for this scenario yet. Run it live once first.
          </p>
        )}
      </div>
    </aside>
  );
}
