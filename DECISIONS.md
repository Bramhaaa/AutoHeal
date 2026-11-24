# Architecture Decision Records

Format: `## ADR-NNN: Title` → **Context**, **Decision**, **Alternatives**, **Consequences**. Keep each to a short paragraph — these double as interview prep notes.

## ADR-001: Agent proposes search/replace edits, not unified diffs
**Context:** LLM-written unified diffs frequently fail `git apply` (bad hunk headers/context).
**Decision:** `propose_patch` takes exact-match search/replace blocks; backend applies them and generates the canonical diff with java-diff-utils.
**Alternatives:** Raw unified diffs; full-file rewrites (token-heavy, risk of accidental changes).
**Consequences:** Removes a whole failure class; edits with ambiguous `search` text are rejected with a precise error fed back to the agent.

## ADR-002: Clone on host, bind-mount into a network-less sandbox
**Context:** Sandbox must have `--network=none`, so it cannot clone; `docker cp` into tmpfs is unreliable.
**Decision:** JGit clones into a per-run host directory (UID 1000), bind-mounted at `/workspace`.
**Alternatives:** Clone inside container with network (unsafe); `docker cp` (breaks with tmpfs/read-only).
**Consequences:** Host must clean up workspaces (sweeper); simple and fast.

## ADR-003: Original failing test is the verification oracle (Test Integrity Policy)
**Context:** If the agent writes both fix and test, it can "pass" by weakening tests.
**Decision:** Patches may only modify `src/main/**` and add new tests; existing tests/build files are immutable; verified = exit 0 + oracle test passes + test count not reduced.
**Alternatives:** Trust agent-written tests; LLM-as-judge.
**Consequences:** Can't fix bugs whose tests are wrong (explicit non-goal); strong, defensible verification claim.

## ADR-004: Read-only rootfs verified + `docker-java-transport-zerodep`
**Context:** PRD §6.1 required a Week-1 spike to confirm offline Maven works with `--read-only` rootfs and a baked `/opt/m2`. Separately, `docker-java-transport-httpclient5` collided with Spring Boot 3.4's managed `httpclient5` 5.4 BOM when dialing Unix sockets.
**Decision:** Keep `--read-only` rootfs enabled with `tmpfs` at `/tmp` (`rw,noexec,nosuid,size=128m`) and bind-mounted `/workspace` (spike passed in 11s, Maven test time 8.7s). Use `docker-java-transport-zerodep` for reliable Unix-socket transport without BOM conflicts.
**Alternatives:** Drop `--read-only`; pin older `httpclient5` (risks breaking Spring Boot starters).
**Consequences:** Strongest container filesystem isolation with zero dependency conflicts across macOS Docker Desktop and Linux VMs.

## ADR-005: Spring `JdbcClient` + Flyway over JPA/Hibernate
**Context:** The PRD §5 schema uses PostgreSQL-native `JSONB` (`incident_events.payload`, `benchmark_reports`), `pgvector` `vector(768)` (`incident_memory.embedding`), `TIMESTAMPTZ`, and `NUMERIC(10, 6)`, while ArchUnit requires `com.autoheal.domain` to contain pure immutable Java 21 `record` types with zero Spring/JPA imports.
**Decision:** Use Spring Boot 3.4's `spring-boot-starter-jdbc` (`JdbcClient`) with Flyway versioned SQL migrations (`V1__initial_schema.sql`) and explicit SQL in `JdbcIncidentRepository`.
**Alternatives:** Spring Data JPA / Hibernate (requires mutable `@Entity` classes, custom `JSONB`/`pgvector` Hibernate types, and risks lazy-loading/N+1 surprises).
**Consequences:** Zero ORM impedance mismatch, full control over Postgres-native SQL (`CAST(... AS jsonb)`, `SELECT ... FOR UPDATE`, `RETURNING`), direct mapping to domain records, and crystal-clear queries easy to defend in interviews.
