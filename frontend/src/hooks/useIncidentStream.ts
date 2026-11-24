"use client";

import { useEffect, useRef, useState } from "react";
import { API_ROUTES } from "@/lib/routes";
import {
  ALL_SSE_EVENT_TYPES,
  type IncidentEventType,
  type IncidentStage,
  type SseEventEnvelope,
} from "@/lib/types";

export type StreamConnectionState =
  | "idle"
  | "connecting"
  | "streaming"
  | "reconnecting"
  | "completed"
  | "failed";

export interface TerminalEntry {
  eventId: number;
  timestamp: string;
  stage: IncidentStage;
  type: IncidentEventType;
  phase: string | null;
  message: string;
  tone: "default" | "error" | "success" | "meta";
}

export interface IncidentStreamState {
  connectionState: StreamConnectionState;
  currentStage: IncidentStage | null;
  queuePosition: number | null;
  currentAttempt: number;
  reductionPercent: number | null;
  isReplay: boolean;
  terminalEntries: TerminalEntry[];
  failureMessage: string | null;
}

const INITIAL_STATE: IncidentStreamState = {
  connectionState: "idle",
  currentStage: null,
  queuePosition: null,
  currentAttempt: 0,
  reductionPercent: null,
  isReplay: false,
  terminalEntries: [],
  failureMessage: null,
};

function classifyLogTone(message: string): TerminalEntry["tone"] {
  if (
    message.includes("BUILD FAILURE") ||
    message.startsWith("[ERROR]") ||
    message.includes("Failures: 1") ||
    message.includes("Errors: 1") ||
    message.includes("Exception") ||
    message.includes("AssertionFailedError")
  ) {
    return "error";
  }
  if (
    message.includes("BUILD SUCCESS") ||
    (message.includes("Tests run:") &&
      message.includes("Failures: 0") &&
      message.includes("Errors: 0"))
  ) {
    return "success";
  }
  return "default";
}

function readString(payload: Record<string, unknown>, key: string): string | null {
  const value = payload[key];
  return typeof value === "string" ? value : null;
}

function readNumber(payload: Record<string, unknown>, key: string): number | null {
  const value = payload[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function formatEventToEntry(envelope: SseEventEnvelope): TerminalEntry | null {
  const { payload, type, stage, eventId, timestamp } = envelope;

  if (type === "LOG_LINE") {
    const message = readString(payload, "message") ?? "";
    const phase = readString(payload, "phase");
    return {
      eventId,
      timestamp,
      stage,
      type,
      phase,
      message,
      tone: classifyLogTone(message),
    };
  }

  if (type === "STAGE_UPDATE") {
    const queuePos = readNumber(payload, "queuePosition");
    const reduction = readNumber(payload, "reductionPercent");
    const branch = readString(payload, "branch");
    let detail = `Stage -> ${stage}`;
    if (queuePos !== null) {
      detail = `Queued for sandbox slot (position ${queuePos})`;
    } else if (branch !== null) {
      detail = `Cloned failing branch ${branch} and running baseline Maven suite`;
    } else if (reduction !== null) {
      detail = `Distilled Maven failure log (${Math.round(reduction)}% noise removed)`;
    } else if (stage === "AGENT_PATCHING") {
      detail = "Agent inspecting workspace and proposing search/replace edits";
    }
    return {
      eventId,
      timestamp,
      stage,
      type,
      phase: "stage",
      message: detail,
      tone: "meta",
    };
  }

  if (type === "ATTEMPT_RESULT") {
    const attempt = readNumber(payload, "attempt") ?? 1;
    const outcome = readString(payload, "outcome") ?? "UNKNOWN";
    const testsRun = readNumber(payload, "testsRun");
    const testsFailed = readNumber(payload, "testsFailed");
    const counts =
      testsRun !== null && testsFailed !== null
        ? ` (tests run: ${testsRun}, failed: ${testsFailed})`
        : "";
    return {
      eventId,
      timestamp,
      stage,
      type,
      phase: "attempt",
      message: `Attempt ${attempt}: ${outcome}${counts}`,
      tone: outcome === "VERIFIED" ? "success" : "error",
    };
  }

  if (type === "COMPLETED") {
    const summary = readString(payload, "rootCauseSummary");
    return {
      eventId,
      timestamp,
      stage,
      type,
      phase: "done",
      message: summary
        ? `Verified green build. Root cause: ${summary}`
        : "Verified green build. Run completed.",
      tone: "success",
    };
  }

  if (type === "FAILED") {
    const reason = readString(payload, "reason") ?? "FAILED";
    const msg = readString(payload, "message");
    return {
      eventId,
      timestamp,
      stage,
      type,
      phase: "failed",
      message: msg ? `Run failed (${reason}): ${msg}` : `Run failed (${reason})`,
      tone: "error",
    };
  }

  const fallbackMessage = readString(payload, "message");
  if (fallbackMessage) {
    return {
      eventId,
      timestamp,
      stage,
      type,
      phase: null,
      message: fallbackMessage,
      tone: "meta",
    };
  }
  return null;
}

export function useIncidentStream(
  runId: string | null,
  streamUrl: string | null,
  isReplayMode: boolean,
  onTerminal?: (runId: string, status: "COMPLETED" | "FAILED") => void,
): IncidentStreamState {
  const [state, setState] = useState<IncidentStreamState>(INITIAL_STATE);
  const onTerminalRef = useRef(onTerminal);
  onTerminalRef.current = onTerminal;

  useEffect(() => {
    if (!runId || !streamUrl) {
      setState(INITIAL_STATE);
      return;
    }

    const seenEventIds = new Set<number>();
    let closedByTerminal = false;

    setState({
      ...INITIAL_STATE,
      connectionState: "connecting",
      currentStage: "QUEUED",
      isReplay: isReplayMode,
    });

    const resolvedUrl = API_ROUTES.resolveStreamUrl(streamUrl);
    const eventSource = new EventSource(resolvedUrl);

    const handleEnvelope = (raw: string) => {
      let envelope: SseEventEnvelope;
      try {
        envelope = JSON.parse(raw) as SseEventEnvelope;
      } catch {
        return;
      }

      if (seenEventIds.has(envelope.eventId)) {
        return;
      }
      seenEventIds.add(envelope.eventId);

      const entry = formatEventToEntry(envelope);
      const payload = envelope.payload ?? {};

      setState((prev) => {
        const nextQueuePos =
          envelope.stage === "QUEUED"
            ? (readNumber(payload, "queuePosition") ?? prev.queuePosition)
            : null;
        const attemptFromPayload = readNumber(payload, "attempt");
        const nextAttempt =
          attemptFromPayload !== null
            ? Math.max(prev.currentAttempt, attemptFromPayload)
            : envelope.stage === "AGENT_PATCHING" && prev.currentAttempt === 0
              ? 1
              : prev.currentAttempt;
        const nextReduction =
          readNumber(payload, "reductionPercent") ?? prev.reductionPercent;
        const replayFlag = payload["replay"] === true ? true : prev.isReplay;

        let nextConn: StreamConnectionState = "streaming";
        let failureMessage = prev.failureMessage;
        if (envelope.type === "COMPLETED") {
          nextConn = "completed";
        } else if (envelope.type === "FAILED") {
          nextConn = "failed";
          const reason = readString(payload, "reason") ?? "FAILED";
          const msg = readString(payload, "message");
          failureMessage = msg ? `${reason}: ${msg}` : reason;
        }

        return {
          connectionState: nextConn,
          currentStage: envelope.stage,
          queuePosition: nextQueuePos,
          currentAttempt: nextAttempt,
          reductionPercent: nextReduction,
          isReplay: replayFlag,
          terminalEntries: entry
            ? [...prev.terminalEntries, entry]
            : prev.terminalEntries,
          failureMessage,
        };
      });

      if (envelope.type === "COMPLETED" || envelope.type === "FAILED") {
        closedByTerminal = true;
        eventSource.close();
        onTerminalRef.current?.(runId, envelope.type);
      }
    };

    const listener = (event: MessageEvent<string>) => {
      handleEnvelope(event.data);
    };

    eventSource.onopen = () => {
      setState((prev) =>
        prev.connectionState === "completed" || prev.connectionState === "failed"
          ? prev
          : { ...prev, connectionState: "streaming" },
      );
    };

    eventSource.onmessage = listener;
    for (const eventType of ALL_SSE_EVENT_TYPES) {
      eventSource.addEventListener(eventType, listener as EventListener);
    }

    eventSource.onerror = () => {
      if (closedByTerminal) {
        return;
      }
      setState((prev) =>
        prev.connectionState === "completed" || prev.connectionState === "failed"
          ? prev
          : { ...prev, connectionState: "reconnecting" },
      );
    };

    return () => {
      closedByTerminal = true;
      for (const eventType of ALL_SSE_EVENT_TYPES) {
        eventSource.removeEventListener(eventType, listener as EventListener);
      }
      eventSource.close();
    };
  }, [runId, streamUrl, isReplayMode]);

  return state;
}
