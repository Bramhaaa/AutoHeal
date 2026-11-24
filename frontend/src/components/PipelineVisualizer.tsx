"use client";

import type { StreamConnectionState } from "@/hooks/useIncidentStream";
import type { IncidentStage } from "@/lib/types";

interface PipelineVisualizerProps {
  currentStage: IncidentStage | null;
  connectionState: StreamConnectionState;
  currentAttempt: number;
  queuePosition: number | null;
  isReplay: boolean;
  reductionPercent: number | null;
}

const PIPELINE_STEPS: readonly {
  stage: IncidentStage;
  stepNumber: number;
  label: string;
}[] = [
  { stage: "INCIDENT_RECEIVED", stepNumber: 1, label: "Incident received" },
  { stage: "LOG_DISTILLED", stepNumber: 2, label: "Log distilled" },
  { stage: "AGENT_PATCHING", stepNumber: 3, label: "Agent patching" },
  { stage: "SANDBOX_VERIFY", stepNumber: 4, label: "Sandbox verify" },
  { stage: "PR_OPENED", stepNumber: 5, label: "Draft PR opened" },
] as const;

const STAGE_ORDER: Record<IncidentStage, number> = {
  QUEUED: 0,
  INCIDENT_RECEIVED: 1,
  LOG_DISTILLED: 2,
  AGENT_PATCHING: 3,
  SANDBOX_VERIFY: 4,
  PR_OPENED: 5,
};

export function PipelineVisualizer({
  currentStage,
  connectionState,
  currentAttempt,
  queuePosition,
  isReplay,
  reductionPercent,
}: PipelineVisualizerProps) {
  const currentRank = currentStage ? STAGE_ORDER[currentStage] : -1;

  return (
    <section
      aria-label="Pipeline progress"
      className="bg-[var(--surface-panel)] border border-[var(--border-hairline)] p-4"
    >
      <div className="flex flex-wrap items-center justify-between gap-2 mb-4">
        <div className="flex items-center gap-2.5">
          <h2 className="font-display font-semibold text-sm text-[var(--ink-primary)]">
            Healing pipeline
          </h2>
          {isReplay && (
            <span className="px-2 py-0.5 text-[11px] font-mono font-medium bg-amber-100 text-[var(--signal-amber)] border border-amber-300">
              Replay
            </span>
          )}
          {currentStage === "QUEUED" && queuePosition !== null && (
            <span className="px-2 py-0.5 text-[11px] font-mono bg-[var(--surface-muted)] text-[var(--ink-secondary)]">
              Queue position {queuePosition}
            </span>
          )}
          {currentAttempt > 0 && (
            <span className="px-2 py-0.5 text-[11px] font-mono bg-[var(--surface-muted)] text-[var(--ink-primary)]">
              Attempt {currentAttempt} of 3
            </span>
          )}
          {reductionPercent !== null && (
            <span className="px-2 py-0.5 text-[11px] font-mono bg-[var(--surface-muted)] text-[var(--ink-secondary)]">
              Log noise -{Math.round(reductionPercent)}%
            </span>
          )}
        </div>

        <div className="text-xs font-mono text-[var(--ink-secondary)]">
          {connectionState === "idle" && "Ready"}
          {connectionState === "connecting" && "Connecting SSE..."}
          {connectionState === "streaming" && "Streaming live"}
          {connectionState === "reconnecting" && "Reconnecting..."}
          {connectionState === "completed" && (
            <span className="text-[var(--signal-emerald)] font-semibold">Verified green</span>
          )}
          {connectionState === "failed" && (
            <span className="text-[var(--signal-crimson)] font-semibold">Failed</span>
          )}
        </div>
      </div>

      <ol className="grid grid-cols-1 sm:grid-cols-5 gap-2">
        {PIPELINE_STEPS.map((step) => {
          const isCompleted =
            connectionState === "completed"
              ? true
              : currentRank > step.stepNumber;
          const isActive =
            connectionState !== "completed" &&
            connectionState !== "failed" &&
            currentRank === step.stepNumber;
          const isFailedStep =
            connectionState === "failed" && currentRank === step.stepNumber;

          let containerClasses =
            "border-[var(--border-hairline)] bg-[var(--surface-canvas)] text-[var(--ink-secondary)]";
          let badgeClasses = "bg-[var(--surface-muted)] text-[var(--ink-secondary)]";

          if (isCompleted) {
            containerClasses =
              "border-emerald-300 bg-emerald-50/60 text-[var(--ink-primary)]";
            badgeClasses = "bg-[var(--signal-emerald)] text-white";
          } else if (isFailedStep) {
            containerClasses =
              "border-red-300 bg-red-50/70 text-[var(--signal-crimson)]";
            badgeClasses = "bg-[var(--signal-crimson)] text-white";
          } else if (isActive) {
            containerClasses =
              "border-[var(--signal-cobalt)] bg-blue-50/70 text-[var(--ink-primary)]";
            badgeClasses = "bg-[var(--signal-cobalt)] text-white";
          }

          return (
            <li
              key={step.stage}
              data-stage={step.stage}
              data-state={
                isCompleted
                  ? "done"
                  : isFailedStep
                    ? "failed"
                    : isActive
                      ? "active"
                      : "pending"
              }
              className={`border px-3 py-2.5 flex items-center gap-2.5 ${containerClasses}`}
            >
              <span
                className={`w-5 h-5 shrink-0 flex items-center justify-center font-mono text-[11px] font-semibold ${badgeClasses}`}
              >
                {isCompleted ? "✓" : step.stepNumber}
              </span>
              <span className="text-xs font-medium leading-tight">{step.label}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
