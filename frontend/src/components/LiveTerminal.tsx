"use client";

import { useEffect, useRef, useState } from "react";
import type { TerminalEntry } from "@/hooks/useIncidentStream";

interface LiveTerminalProps {
  entries: TerminalEntry[];
  isStreaming: boolean;
}

export function LiveTerminal({ entries, isStreaming }: LiveTerminalProps) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  useEffect(() => {
    if (!autoScroll || !scrollRef.current) {
      return;
    }
    scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [entries, autoScroll]);

  return (
    <div className="bg-[var(--surface-terminal)] border border-slate-800 flex flex-col h-[420px]">
      <div className="px-3.5 py-2 border-b border-slate-800 flex items-center justify-between text-xs font-mono text-slate-300">
        <div className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className={`w-2 h-2 rounded-full ${
              isStreaming ? "bg-emerald-400 animate-pulse" : "bg-slate-500"
            }`}
          />
          <span>Sandbox Maven output</span>
          <span className="text-slate-500">({entries.length} lines)</span>
        </div>

        <button
          type="button"
          onClick={() => setAutoScroll((prev) => !prev)}
          className="px-2 py-0.5 text-[11px] border border-slate-700 text-slate-300 hover:bg-slate-800 cursor-pointer"
        >
          {autoScroll ? "Auto-scroll: on" : "Auto-scroll: paused"}
        </button>
      </div>

      <div
        ref={scrollRef}
        data-testid="live-terminal"
        className="flex-1 overflow-y-auto p-3.5 font-mono text-xs leading-relaxed space-y-0.5"
      >
        {entries.length === 0 ? (
          <div className="h-full flex flex-col items-center justify-center text-center text-slate-400 px-6">
            <p className="text-slate-300 font-medium">No sandbox run active</p>
            <p className="mt-1 text-slate-400 max-w-md">
              Select a scenario on the left and click Simulate crash to stream live
              Maven test output from the isolated Docker container.
            </p>
          </div>
        ) : (
          entries.map((entry) => {
            let lineColor = "text-slate-200";
            if (entry.tone === "error") {
              lineColor = "text-red-400 font-medium";
            } else if (entry.tone === "success") {
              lineColor = "text-emerald-400 font-semibold";
            } else if (entry.tone === "meta") {
              lineColor = "text-sky-300";
            }

            return (
              <div key={entry.eventId} className="flex gap-3 break-words">
                <span className="shrink-0 text-slate-500 select-none w-8 text-right">
                  {entry.eventId}
                </span>
                <span className={lineColor}>{entry.message}</span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
