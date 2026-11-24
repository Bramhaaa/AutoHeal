# Current Working Memory

- **Current Phase:** Week 4 in progress (Slice 15 done → Next: Slice 16 GitHub Webhook & Slice 17 `pgvector` RAG)
- **Current Branch:** `main`
- **Completed Work:**
  - PRD v1.1 (`PRD.md`, single source of truth).
  - Engineering rules (`AGENTS.md`), ADRs (`DECISIONS.md` ADR-001..005), learning log (`docs/LEARNING.md` Slices 1–15), quality skills, and persistent memory.
  - **Week 1 (Slices 1–4):** Backend skeleton + quality gates (`f5b06c2`), `demo-service` 4 deterministic scenarios (`219d01b`), hardened `DockerSandboxRunner` + `autoheal-maven-runner:java21` (`d640e20`), `JGitWorkspaceManager` + XXE-safe `SurefireReportParser` (`4615577`).
  - **Week 2 (Slices 5–9):** Path-safe `WorkspaceTools` + `TestIntegrityPolicy` + `PatchApplier` (`e04de62`), `LogDistiller` (`43a1331`), `LlmClient` + bounded `AgenticHealingService` (`6086a6a`), `GeminiLlmClient` (`477a002`), `GitHubRestPrClient` + `PullRequestBodyFormatter` (`ce4f93d`).
  - **Week 3 (Slices 10–14 — MVP Checkpoint):** Postgres+pgvector Flyway & `JdbcIncidentRepository` (`d68d0f0`), `ScenarioCatalog` & persisted SSE (`93e2406`), async `IncidentOrchestratorService` + Replay Mode (`785e08b`), Bucket4j abuse protection & REST/SSE controllers (`539e391`), `SandboxSweeperService` + Actuator + Dockerfile + Nginx/Compose VM stack (`f9b1ccf`).
  - **Week 4 (Slice 15):** Next.js App Router Live Incident Playground & Benchmarks UI (`frontend/`, `FR-1.1`–`FR-1.5`) — typed API client, named SSE `EventSource` hook with `eventId` deduplication, unified diff parser + Monaco side-by-side DiffEditor, 5-stage pipeline visualizer, live terminal, 429→Replay fallback, and Playwright E2E smoke tests (`2/2` passing, `tsc --noEmit` & `eslint --max-warnings 0` & `next build` green).
- **Next Recommended Task:**
  - **Week 4, Slice 16 (`PRD §4.1`):** GitHub `workflow_run` webhook (`POST /api/v1/webhooks/github`) with constant-time HMAC-SHA256 verification, `X-GitHub-Delivery` deduplication, Actions log zip download, and triggering the internal healing pipeline.
  - Followed by **Slice 17 (`FR-2.3`)**: `incident_memory` `pgvector` RAG seed corpus + self-populating memory + eval leakage guard.
- **Known Minor Gaps (non-blocking):**
  - `DockerSandboxRunner.sweepExpiredContainers(Duration)` has unit coverage on `SandboxSweeperService` (mocked runner) but no dedicated Docker integration test yet.
  - `DefaultLiveIncidentExecutor` does not yet wire `GitHubPrClient` PR creation directly into the post-verify step (needs `modifiedFiles` map on `HealingResult`); add before live VM demo.
- **Blockers:** None (ensure Docker Desktop is running when executing `DockerSandboxRunnerTest` and Testcontainers tests).
