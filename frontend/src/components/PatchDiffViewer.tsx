"use client";

import dynamic from "next/dynamic";
import { useMemo, useState } from "react";
import { parseUnifiedDiff } from "@/lib/diffParser";

const MonacoDiffEditor = dynamic(
  () => import("@monaco-editor/react").then((mod) => mod.DiffEditor),
  {
    ssr: false,
    loading: () => (
      <div className="h-[360px] flex items-center justify-center bg-[var(--surface-canvas)] text-xs font-mono text-[var(--ink-secondary)]">
        Loading Monaco diff editor...
      </div>
    ),
  },
);

interface PatchDiffViewerProps {
  unifiedDiff: string | null;
}

export function PatchDiffViewer({ unifiedDiff }: PatchDiffViewerProps) {
  const parsedFiles = useMemo(
    () => (unifiedDiff ? parseUnifiedDiff(unifiedDiff) : []),
    [unifiedDiff],
  );
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [viewMode, setViewMode] = useState<"side-by-side" | "raw">("side-by-side");

  if (!unifiedDiff || parsedFiles.length === 0) {
    return (
      <div className="h-[420px] border border-[var(--border-hairline)] bg-[var(--surface-panel)] flex flex-col items-center justify-center text-center px-6">
        <p className="font-display font-semibold text-sm text-[var(--ink-primary)]">
          No verified patch diff yet
        </p>
        <p className="text-xs text-[var(--ink-secondary)] mt-1 max-w-md">
          Once the agent proposes edits and the Docker sandbox verifies a green build,
          the canonical server-generated diff will appear here side by side.
        </p>
      </div>
    );
  }

  const safeIndex = selectedIndex < parsedFiles.length ? selectedIndex : 0;
  const activeFile = parsedFiles[safeIndex];

  return (
    <div
      data-testid="patch-diff-viewer"
      className="border border-[var(--border-hairline)] bg-[var(--surface-panel)] flex flex-col h-[420px]"
    >
      <div className="px-3 py-2 border-b border-[var(--border-hairline)] bg-[var(--surface-canvas)] flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-1.5">
          {parsedFiles.map((file, idx) => {
            const active = idx === safeIndex;
            return (
              <button
                key={file.path}
                type="button"
                onClick={() => setSelectedIndex(idx)}
                className={`px-2.5 py-1 text-xs font-mono border transition-colors cursor-pointer ${
                  active
                    ? "border-[var(--signal-cobalt)] bg-white text-[var(--ink-primary)] font-medium"
                    : "border-[var(--border-hairline)] bg-transparent text-[var(--ink-secondary)] hover:bg-white"
                }`}
              >
                <span>{file.path}</span>
                <span className="ml-2 text-[var(--signal-emerald)]">+{file.additions}</span>
                <span className="ml-1 text-[var(--signal-crimson)]">-{file.deletions}</span>
              </button>
            );
          })}
        </div>

        <div className="flex items-center border border-[var(--border-hairline)] bg-white text-xs">
          <button
            type="button"
            onClick={() => setViewMode("side-by-side")}
            className={`px-2.5 py-1 cursor-pointer ${
              viewMode === "side-by-side"
                ? "bg-[var(--ink-primary)] text-white"
                : "text-[var(--ink-secondary)]"
            }`}
          >
            Side by side
          </button>
          <button
            type="button"
            onClick={() => setViewMode("raw")}
            className={`px-2.5 py-1 border-l border-[var(--border-hairline)] cursor-pointer ${
              viewMode === "raw"
                ? "bg-[var(--ink-primary)] text-white"
                : "text-[var(--ink-secondary)]"
            }`}
          >
            Raw diff
          </button>
        </div>
      </div>

      <div className="flex-1 min-h-0">
        {viewMode === "raw" || !activeFile ? (
          <pre className="h-full overflow-auto p-3.5 font-mono text-xs bg-[var(--surface-terminal)] text-slate-200 leading-relaxed">
            {unifiedDiff}
          </pre>
        ) : (
          <MonacoDiffEditor
            height="100%"
            language={activeFile.language}
            original={activeFile.original}
            modified={activeFile.modified}
            theme="vs"
            options={{
              readOnly: true,
              renderSideBySide: true,
              minimap: { enabled: false },
              scrollBeyondLastLine: false,
              fontSize: 12,
              lineNumbers: "on",
              wordWrap: "off",
            }}
          />
        )}
      </div>
    </div>
  );
}
