# Current Working Memory

- **Current Phase:** End of Week 4 Complete (Slices 15–17 done) → Starting Week 5 (Evaluation Harness, Benchmarks API, PR wiring, Deploy & README)
- **Current Branch:** `main`
- **Completed Work:**
  - PRD v1.1 (`PRD.md`, single source of truth).
  - Engineering rules (`AGENTS.md`), ADRs (`DECISIONS.md` ADR-001..005), learning log (`docs/LEARNING.md` Slices 1–17), quality skills, and persistent memory.
  - **Week 1 (Slices 1–4):** Backend skeleton + quality gates (`f5b06c2`), `demo-service` 4 deterministic scenarios (`219d01b`), hardened `DockerSandboxRunner` + `autoheal-maven-runner:java21` (`d640e20`), `JGitWorkspaceManager` + XXE-safe `SurefireReportParser` (`4615577`).
  - **Week 2 (Slices 5–9):** Path-safe `WorkspaceTools` + `TestIntegrityPolicy` + `PatchApplier` (`e04de62`), `LogDistiller` (`43a1331`), `LlmClient` + bounded `AgenticHealingService` (`6086a6a`), `GeminiLlmClient` (`477a002`), `GitHubRestPrClient` + `PullRequestBodyFormatter` (`ce4f93d`).
  - **Week 3 (Slices 10–14 — MVP Checkpoint):** Postgres+pgvector Flyway & `JdbcIncidentRepository` (`d68d0f0`), `ScenarioCatalog` & persisted SSE (`93e2406`), async `IncidentOrchestratorService` + Replay Mode (`785e08b`), Bucket4j abuse protection & REST/SSE controllers (`539e391`), `SandboxSweeperService` + Actuator + Dockerfile + Nginx/Compose VM stack (`f9b1ccf`).
  - **Week 4 (Slices 15–17 — Complete):**
    - Slice 15 (`6038c77`): Next.js App Router Live Incident Playground & Benchmarks UI (`frontend/`, `FR-1.1`–`FR-1.5`) — `2/2` Playwright E2E tests, `tsc --noEmit`, `eslint --max-warnings 0`, `next build` green.
    - Slice 16 (`5ddd515`): GitHub `workflow_run` webhook (`POST /api/v1/webhooks/github`, `PRD §4.1`) — constant-time `MessageDigest.isEqual` HMAC-SHA256 verification, `X-GitHub-Delivery` LRU de-duplication, bounded 512 KB Actions log zip client, and `IncidentOrchestratorService.triggerWebhookRun(...)`.
    - Slice 17: `pgvector` Historical RAG (`FR-2.3`) — `JdbcIncidentMemoryRepository` (cosine `<=>` + `origin_bug_id` eval leakage guard tested against `pgvector:pg16`), `GeminiEmbeddingClient` (768-dim), 30-entry `IncidentMemorySeedCatalog`, self-populating `IncidentMemoryService`, wired into `HealingContext` → `AgenticHealingService` and `DefaultLiveIncidentExecutor`. (`112/112` Maven tests, Spotless `136/136`, Error Prone 0 warnings, ArchUnit `4/4`, JaCoCo ≥80% met).
- **Next Recommended Task:**
  - **Week 5, Slice 18:** Wire `GitHubPrClient` PR creation into `DefaultLiveIncidentExecutor` + add `GET /api/v1/benchmarks/summary` + daily demo repo hygiene job (`FR-5.3`), followed by the evaluation harness (`autoheal-eval`) + 25–30 curated bug corpus + final `README.md`.
- **Known Minor Gaps (non-blocking):**
  - `DockerSandboxRunner.sweepExpiredContainers(Duration)` has unit coverage on `SandboxSweeperService` (mocked runner) but no dedicated Docker integration test yet.
  - `DefaultLiveIncidentExecutor` does not yet wire `GitHubPrClient` PR creation directly into the post-verify step (needs `modifiedFiles` map on `HealingResult`); add before live VM demo.
- **Blockers:** None (ensure Docker Desktop is running when executing `DockerSandboxRunnerTest` and Testcontainers tests).
