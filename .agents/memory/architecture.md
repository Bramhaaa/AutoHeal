# AutoHeal — Architecture

## Package Layers (enforced by `ArchitectureTest` via ArchUnit)
`com.autoheal.api` → `com.autoheal.service` → `com.autoheal.domain` / `com.autoheal.infra`

- `domain`: Pure records and port interfaces (`SandboxRunner`, `WorkspaceManager`, `TestSuiteReport`). Zero Spring/Docker/Git/HTTP imports.
- `service`: Business logic, orchestration, parsing (`SurefireReportParser`), policy enforcement, agent loop.
- `infra`: Adapters implementing `domain` ports (`DockerSandboxRunner`, `JGitWorkspaceManager`, GitHub/LLM/DB adapters).
- `api`: REST controllers & SSE endpoints (no business logic, no direct `infra` calls).

## Execution Pipeline
1. Trigger (Playground or GitHub `workflow_run` webhook) → `IncidentRun` queued (`Semaphore(3)` bounds concurrent sandboxes).
2. Host-side JGit clones failing branch into per-run temp workspace (`AutoCloseable` cleanup).
3. Log distiller trims Maven/JVM noise; collects recent commit diffs (+ optional `pgvector` RAG lookup).
4. Agentic loop (max 3 attempts): local path-safe tools (`list_files`, `read_file`, `search_code`) → `propose_patch` (search/replace edits) → Test Integrity Policy check → hardened Docker sandbox (`mvn -o test`).
5. Verified green build (exit 0 + oracle test passes + test count not reduced) → GitHub Draft PR + SSE stream persisted in Postgres.

## Sandbox Security Invariants (`SandboxContainerSpec`)
`--network=none`, `--memory=768m`, `--memory-swap=768m`, `--cpus=1.0`, `--pids-limit=256`, `--cap-drop=ALL`, `no-new-privileges`, `--read-only` rootfs + `tmpfs /tmp`, UID `1000:1000`, `autoheal=true` label, 60s hard timeout, no host secrets in env.
