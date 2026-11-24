"use client";

import { useEffect, useState } from "react";
import { fetchBenchmarkSummary } from "@/lib/api";
import type { BenchmarkSummary } from "@/lib/types";

function formatPct(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

function formatSec(ms: number): string {
  return `${(ms / 1000).toFixed(1)} s`;
}

export function BenchmarksView() {
  const [summary, setSummary] = useState<BenchmarkSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetchBenchmarkSummary(controller.signal)
      .then((data) => {
        setSummary(data);
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setError(
          err instanceof Error
            ? err.message
            : "Could not fetch benchmark summary.",
        );
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });

    return () => controller.abort();
  }, []);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display font-bold text-2xl tracking-tight text-[var(--ink-primary)]">
          Evaluation Benchmarks
        </h1>
        <p className="text-sm text-[var(--ink-secondary)] mt-1 max-w-2xl">
          Quantitative fix rates, latency, token cost, and ablation metrics across the
          curated Java/Spring bug corpus.
        </p>
      </div>

      {loading ? (
        <div
          aria-busy="true"
          className="h-48 border border-[var(--border-hairline)] bg-[var(--surface-panel)] animate-pulse"
        />
      ) : error ? (
        <div className="p-5 border border-[var(--signal-crimson)] bg-red-50 text-sm">
          <p className="font-semibold text-[var(--signal-crimson)]">
            Unable to load benchmark report
          </p>
          <p className="text-xs text-[var(--ink-secondary)] mt-1">{error}</p>
        </div>
      ) : !summary ? (
        <div className="p-8 border border-[var(--border-hairline)] bg-[var(--surface-panel)]">
          <h2 className="font-display font-semibold text-base text-[var(--ink-primary)]">
            No benchmark report published yet
          </h2>
          <p className="text-sm text-[var(--ink-secondary)] mt-1.5 max-w-xl">
            Run the AutoHeal evaluation harness against the curated bug corpus to
            populate pass@1, pass@3, ablation comparisons, and the failure taxonomy.
          </p>
        </div>
      ) : (
        <div className="space-y-6">
          <dl className="grid grid-cols-2 lg:grid-cols-5 gap-3">
            <div className="p-4 border border-[var(--border-hairline)] bg-[var(--surface-panel)]">
              <dt className="text-xs text-[var(--ink-secondary)]">Corpus size</dt>
              <dd className="font-mono font-semibold text-xl mt-1">
                {summary.totalBugs} bugs
              </dd>
            </div>
            <div className="p-4 border border-[var(--border-hairline)] bg-[var(--surface-panel)]">
              <dt className="text-xs text-[var(--ink-secondary)]">Pass@1</dt>
              <dd className="font-mono font-semibold text-xl text-[var(--signal-emerald)] mt-1">
                {formatPct(summary.passAt1Rate)}
              </dd>
            </div>
            <div className="p-4 border border-[var(--border-hairline)] bg-[var(--surface-panel)]">
              <dt className="text-xs text-[var(--ink-secondary)]">Pass@3</dt>
              <dd className="font-mono font-semibold text-xl text-[var(--signal-emerald)] mt-1">
                {formatPct(summary.passAt3Rate)}
              </dd>
            </div>
            <div className="p-4 border border-[var(--border-hairline)] bg-[var(--surface-panel)]">
              <dt className="text-xs text-[var(--ink-secondary)]">Median / p90 time</dt>
              <dd className="font-mono font-semibold text-xl mt-1">
                {formatSec(summary.medianDurationMs)} / {formatSec(summary.p90DurationMs)}
              </dd>
            </div>
            <div className="p-4 border border-[var(--border-hairline)] bg-[var(--surface-panel)]">
              <dt className="text-xs text-[var(--ink-secondary)]">Mean cost / run</dt>
              <dd className="font-mono font-semibold text-xl mt-1">
                ${summary.meanCostUsd.toFixed(4)}
              </dd>
            </div>
          </dl>

          {summary.ablations.length > 0 && (
            <section className="border border-[var(--border-hairline)] bg-[var(--surface-panel)]">
              <div className="px-4 py-3 border-b border-[var(--border-hairline)]">
                <h2 className="font-display font-semibold text-sm">Ablation study</h2>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead className="bg-[var(--surface-canvas)] text-[var(--ink-secondary)] border-b border-[var(--border-hairline)]">
                    <tr>
                      <th className="py-2.5 px-4 font-medium">Configuration</th>
                      <th className="py-2.5 px-4 font-medium">Pass@1</th>
                      <th className="py-2.5 px-4 font-medium">Pass@3</th>
                      <th className="py-2.5 px-4 font-medium">Median time</th>
                      <th className="py-2.5 px-4 font-medium">Mean cost</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[var(--border-hairline)] font-mono">
                    {summary.ablations.map((row) => (
                      <tr key={row.label}>
                        <td className="py-2.5 px-4 font-sans font-medium text-[var(--ink-primary)]">
                          {row.label}
                        </td>
                        <td className="py-2.5 px-4">{formatPct(row.passAt1Rate)}</td>
                        <td className="py-2.5 px-4">{formatPct(row.passAt3Rate)}</td>
                        <td className="py-2.5 px-4">{formatSec(row.medianDurationMs)}</td>
                        <td className="py-2.5 px-4">${row.meanCostUsd.toFixed(4)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          {summary.failureTaxonomy.length > 0 && (
            <section className="border border-[var(--border-hairline)] bg-[var(--surface-panel)]">
              <div className="px-4 py-3 border-b border-[var(--border-hairline)]">
                <h2 className="font-display font-semibold text-sm">Failure taxonomy</h2>
              </div>
              <ul className="divide-y divide-[var(--border-hairline)] text-xs">
                {summary.failureTaxonomy.map((bucket) => (
                  <li
                    key={bucket.reason}
                    className="px-4 py-2.5 flex items-center justify-between"
                  >
                    <span className="font-mono text-[var(--ink-primary)]">
                      {bucket.reason}
                    </span>
                    <span className="font-mono font-semibold text-[var(--signal-crimson)]">
                      {bucket.count}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      )}
    </div>
  );
}
