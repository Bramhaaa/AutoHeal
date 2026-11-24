"use client";

import type { IncidentRunResponse } from "@/lib/types";

interface RunSummaryBarProps {
  run: IncidentRunResponse;
}

function formatDuration(durationMs: number | null): string {
  if (durationMs === null) return "--";
  if (durationMs < 1000) return `${durationMs} ms`;
  return `${(durationMs / 1000).toFixed(1)} s`;
}

function formatCost(costUsd: number): string {
  if (!Number.isFinite(costUsd) || costUsd === 0) return "$0.0000";
  return `$${costUsd.toFixed(4)}`;
}

export function RunSummaryBar({ run }: RunSummaryBarProps) {
  const succeeded = run.status === "SUCCEEDED";
  const totalTokens = run.totalPromptTokens + run.totalCompletionTokens;

  return (
    <section
      data-testid="run-summary"
      className="bg-[var(--surface-panel)] border border-[var(--border-hairline)] p-4 space-y-3"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1 max-w-2xl">
          <div className="flex items-center gap-2">
            <span
              className={`px-2 py-0.5 text-xs font-mono font-semibold ${
                succeeded
                  ? "bg-emerald-100 text-[var(--signal-emerald)]"
                  : "bg-red-100 text-[var(--signal-crimson)]"
              }`}
            >
              {succeeded ? "Verified fix" : `Failed: ${run.failureReason ?? "Unknown"}`}
            </span>
            <span className="text-xs font-mono text-[var(--ink-secondary)]">
              Run {run.runId.slice(0, 8)}
            </span>
          </div>
          {run.rootCauseSummary && (
            <p className="text-sm text-[var(--ink-primary)] leading-relaxed">
              {run.rootCauseSummary}
            </p>
          )}
        </div>

        {run.prUrl && (
          <a
            href={run.prUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="shrink-0 px-3.5 py-2 text-xs font-display font-semibold bg-[var(--signal-emerald)] text-white hover:opacity-95 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--signal-emerald)]"
          >
            Open GitHub Draft PR
          </a>
        )}
      </div>

      <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-3 border-t border-[var(--border-hairline)] text-xs">
        <div>
          <dt className="text-[var(--ink-secondary)]">Attempts used</dt>
          <dd className="font-mono font-semibold text-[var(--ink-primary)] mt-0.5">
            {run.attemptsUsed} / 3
          </dd>
        </div>
        <div>
          <dt className="text-[var(--ink-secondary)]">Wall duration</dt>
          <dd className="font-mono font-semibold text-[var(--ink-primary)] mt-0.5">
            {formatDuration(run.durationMs)}
          </dd>
        </div>
        <div>
          <dt className="text-[var(--ink-secondary)]">Total tokens</dt>
          <dd className="font-mono font-semibold text-[var(--ink-primary)] mt-0.5">
            {totalTokens.toLocaleString()}
          </dd>
        </div>
        <div>
          <dt className="text-[var(--ink-secondary)]">Estimated LLM cost</dt>
          <dd className="font-mono font-semibold text-[var(--ink-primary)] mt-0.5">
            {formatCost(run.estimatedCostUsd)}
          </dd>
        </div>
      </dl>
    </section>
  );
}
