# Current Working Memory

- **Current Phase:** End of Week 1 → Starting Week 2 (Agentic Core)
- **Current Branch:** `main` (pushed to `origin/main` @ `4615577`)
- **Completed Work:**
  - PRD reviewed and revised to v1.1 (`PRD.md`; original kept at `PRD.v1.0.md`).
  - Engineering rules (`AGENTS.md`), ADRs (`DECISIONS.md` ADR-001..004), and quality skills installed.
  - Slice 1 (`f5b06c2`): Backend skeleton + Error Prone, Spotless, JaCoCo, ArchUnit gates.
  - Slice 2 (`219d01b`): `demo-service` with 4 deterministic bug scenarios (20/20 race verified).
  - Slice 3 (`d640e20`): `autoheal-maven-runner:java21` image (`8.7s` offline `--read-only` spike) + hardened `DockerSandboxRunner`.
  - Slice 4 (`4615577`): `JGitWorkspaceManager` + XXE-safe `SurefireReportParser`.
  - Independent verification completed: clean `mvn -B clean verify` passes (17/17 tests), all gates probed and confirmed firing.
- **Next Recommended Task:**
  - **Week 2, Slice 5 (`FR-3.1`, `FR-3.4`, `ADR-001`):** Test Integrity Policy, Search/Replace Patch Applier + server-side diff (`java-diff-utils`), and path-traversal-safe local workspace tools (`list_files`, `read_file`, `search_code`).
- **Known Minor Gaps (non-blocking):**
  - `DockerSandboxRunner.sweepExpiredContainers(Duration)` has no dedicated test yet (DockerSandboxRunner at 73% line coverage).
  - On Linux VMs (Week 3 deploy), ensure backend runs as UID `1000` or sets POSIX permissions so container UID `1000` can write `target/` in bind-mounted `/workspace`.
- **Blockers:** None (ensure Docker Desktop is running when executing `DockerSandboxRunnerTest`).
