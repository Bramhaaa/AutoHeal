# AutoHeal — Semantic Index

Start searches here before scanning the repo.

## Top-Level Modules
- `backend/` — Spring Boot 3.4 Maven service + quality gates (`pom.xml`, `.mvn/jvm.config`).
- `demo-service/` — Standalone Spring Boot demo service with 4 deterministic bug scenarios (`FR-1.1`).
- `sandbox/` — Pre-warmed Maven runner `Dockerfile` (`autoheal-maven-runner:java21`, `FR-4.1`).
- `docs/` — `LEARNING.md` (per-slice concepts & interview Q&As).
- `.agents/` — Skills (`skills/`), persistent project memory (`memory/`), templates (`templates/`).

## Implemented Features & Key Files
- **Build & Architecture Gates:** `backend/pom.xml` (Error Prone, Spotless, JaCoCo ≥80% on `service`/`domain`), `backend/src/test/java/com/autoheal/ArchitectureTest.java`.
- **Demo Bug Scenarios (`FR-1.1`):** `demo-service/src/main/java/com/autoheal/demo/{payment,pagination,inventory,order}/`, patches in `demo-service/scenarios/*.patch`, branch generator `demo-service/scripts/create-scenario-branches.sh`.
- **Hardened Docker Sandbox (`FR-4.1`–`FR-4.3`, §6.1):**
  - Port & models: `backend/src/main/java/com/autoheal/domain/{SandboxRunner,SandboxRequest,SandboxResult}.java`
  - Docker adapter: `backend/src/main/java/com/autoheal/infra/docker/{SandboxContainerSpec,DockerSandboxRunner,DockerSandboxClientFactory}.java`
- **Host Workspace Cloning (`FR-2.2`):**
  - Port & handle: `backend/src/main/java/com/autoheal/domain/{WorkspaceManager,WorkspaceHandle}.java`
  - JGit adapter: `backend/src/main/java/com/autoheal/infra/git/JGitWorkspaceManager.java`
- **Surefire Oracle Parser (`FR-3.4`):**
  - Models: `backend/src/main/java/com/autoheal/domain/{TestCaseId,TestSuiteReport}.java`
  - Parser (XXE-safe): `backend/src/main/java/com/autoheal/service/SurefireReportParser.java`
