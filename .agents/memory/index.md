# AutoHeal — Semantic Index

Start searches here before scanning the repo.

## Top-Level Modules
- `backend/` — Spring Boot 3.4 Maven service + quality gates (`pom.xml`, `Dockerfile`, `.mvn/jvm.config`).
- `frontend/` — Next.js App Router Live Incident Playground & Benchmarks UI (`FR-1.1`–`FR-1.5`, TypeScript strict, Tailwind v4, Monaco Diff Editor, Playwright E2E).
- `demo-service/` — Standalone Spring Boot demo service with 4 deterministic bug scenarios (`FR-1.1`).
- `sandbox/` — Pre-warmed Maven runner `Dockerfile` (`autoheal-maven-runner:java21`, `FR-4.1`).
- `infra/nginx/` — Production Nginx reverse proxy config (`autoheal.conf`, SSE `proxy_buffering off`).
- `docs/` — `LEARNING.md` (per-slice concepts & interview Q&As, Slices 1–14).
- `.agents/` — Skills (`skills/`), persistent project memory (`memory/`), templates (`templates/`).
- Root deploy files: `docker-compose.yml` (local Postgres+pgvector), `docker-compose.prod.yml` (VM stack), `.env.example`.

## Implemented Features & Key Files
- **Build & Architecture Gates:** `backend/pom.xml` (Error Prone, Spotless, JaCoCo ≥80% on `service`/`domain`), `backend/src/test/java/com/autoheal/ArchitectureTest.java`.
- **Demo Bug Scenarios (`FR-1.1`):** `demo-service/src/main/java/com/autoheal/demo/{payment,pagination,inventory,order}/`, patches in `demo-service/scenarios/*.patch`, branch generator `demo-service/scripts/create-scenario-branches.sh`.
- **Hardened Docker Sandbox (`FR-4.1`–`FR-4.3`, §6.1):**
  - Port & models: `backend/src/main/java/com/autoheal/domain/{SandboxRunner,SandboxRequest,SandboxResult}.java`
  - Docker adapter & Spring config: `backend/src/main/java/com/autoheal/infra/docker/{SandboxContainerSpec,DockerSandboxRunner,DockerSandboxClientFactory,DockerSandboxConfig}.java`
  - Scheduled sweeper: `backend/src/main/java/com/autoheal/service/SandboxSweeperService.java`
- **Host Workspace Cloning (`FR-2.2`):**
  - Port & handle: `backend/src/main/java/com/autoheal/domain/{WorkspaceManager,WorkspaceHandle}.java`
  - JGit adapter & config: `backend/src/main/java/com/autoheal/infra/git/{JGitWorkspaceManager,WorkspaceConfig}.java`
- **Surefire Oracle Parser (`FR-3.4`):**
  - Models & parser: `backend/src/main/java/com/autoheal/domain/{TestCaseId,TestSuiteReport}.java`, `backend/src/main/java/com/autoheal/service/SurefireReportParser.java`
- **Path-Safe Tools, Test Integrity Policy & Patch Applier (`FR-3.1`, `FR-3.4`, `ADR-001`, `ADR-003`):**
  - Models: `backend/src/main/java/com/autoheal/domain/{SearchMatch,PathTraversalException,SearchReplaceEdit,NewTestFile,PatchProposal,PolicyValidationResult,VerificationResult,AppliedPatch,PatchApplyException}.java`
  - Services: `backend/src/main/java/com/autoheal/service/{WorkspacePathResolver,WorkspaceTools,TestIntegrityPolicy,PatchApplier}.java`
- **Log Distiller (`FR-2.1`):** `backend/src/main/java/com/autoheal/domain/DistilledLog.java`, `backend/src/main/java/com/autoheal/service/LogDistiller.java`.
- **Bounded Agentic Loop & Gemini LLM Adapter (`FR-3.1`–`3.3`):**
  - Port & models: `backend/src/main/java/com/autoheal/domain/{LlmClient,LlmMessage,LlmToolCall,LlmTurnResponse,LlmUsage,LlmException,AttemptOutcome,AttemptRecord,HealingContext,HealingResult}.java`
  - Orchestrator & adapter: `backend/src/main/java/com/autoheal/service/AgenticHealingService.java`, `backend/src/main/java/com/autoheal/infra/llm/{GeminiProperties,GeminiLlmClient}.java`
- **GitHub Draft PR Integration (`FR-5.1`, `FR-5.2`):**
  - Port & models: `backend/src/main/java/com/autoheal/domain/{GitHubPrClient,PullRequestRequest,PullRequestResult,GitHubException}.java`
  - Formatter & REST adapter: `backend/src/main/java/com/autoheal/service/PullRequestBodyFormatter.java`, `backend/src/main/java/com/autoheal/infra/github/{GitHubProperties,GitHubRestPrClient}.java`
- **PostgreSQL 16 + `pgvector` Persistence (`PRD §5`, `ADR-005`):**
  - Migration: `backend/src/main/resources/db/migration/V1__initial_schema.sql`
  - Port & models: `backend/src/main/java/com/autoheal/domain/{IncidentRepository,IncidentRun,IncidentAttempt,LlmCallRecord,IncidentEvent,IncidentSource,IncidentStatus,IncidentStage,IncidentEventType}.java`
  - Adapter & Testcontainers: `backend/src/main/java/com/autoheal/infra/persistence/JdbcIncidentRepository.java`, `backend/src/test/java/com/autoheal/PostgresTestContainer.java`, `backend/src/test/resources/docker-java.properties`
- **Async Pipeline, SSE Stream, Replay & Abuse Protection (`FR-1.1`–`1.2`, `PRD §4.1`–`4.2`, `§6.2`–`6.3`):**
  - Services: `backend/src/main/java/com/autoheal/service/{ScenarioCatalog,IncidentEventStreamService,IncidentOrchestratorService,DefaultLiveIncidentExecutor,PipelineProperties,AbuseProtectionService,AbuseProtectionProperties}.java`
  - REST/SSE API: `backend/src/main/java/com/autoheal/api/{ScenarioController,IncidentController,GlobalExceptionHandler,WebCorsConfig,ScenarioResponse,SimulateRequest,SimulateResponse,IncidentRunResponse,SseEventEnvelope,ApiErrorResponse}.java`
- **Live Incident Playground & Benchmarks Frontend (`FR-1.1`–`FR-1.5`):**
  - Routes, types, API & diff parser: `frontend/src/lib/{routes,types,api,diffParser}.ts`
  - Named SSE hook: `frontend/src/hooks/useIncidentStream.ts`
  - UI components & pages: `frontend/src/components/{TopNav,ScenarioSelector,PipelineVisualizer,LiveTerminal,PatchDiffViewer,RunSummaryBar,PlaygroundWorkbench,BenchmarksView}.tsx`, `frontend/src/app/{layout,page}.tsx`, `frontend/src/app/benchmarks/page.tsx`
  - Playwright smoke tests: `frontend/tests/playground.spec.ts`
