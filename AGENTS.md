# AGENTS.md — AutoHeal engineering rules (always on)

Source of truth: `PRD.md` (v1.1). Reference requirement IDs (e.g. `FR-4.2`) in commits, tests, and PR descriptions.

## Stack (do not change without an ADR in DECISIONS.md)
- Backend: Java 21, Spring Boot 3.3+, Maven, docker-java, JGit, Flyway, PostgreSQL 16 + pgvector, Bucket4j.
- Frontend: Next.js (App Router), TypeScript (strict), Tailwind, Monaco diff editor.
- Tests: JUnit 5, AssertJ, Mockito (only at true boundaries), Testcontainers for Postgres/Docker integration tests, Playwright for UI.

## Workflow (mandatory)
1. Small slices: implement one FR (or less) at a time. No multi-module "big bang" generations.
2. Use the `test-driven-development` skill for all backend logic: failing test first, then code.
3. Use the `systematic-debugging` skill for any failure. No guess-and-retry patches.
4. Use the `verification-before-completion` skill: never claim "done/works/fixed" without running the command and showing its output.
5. Before finishing a slice, run the `autoheal-quality-gate` skill.
6. Any non-obvious design choice → add a short entry to `DECISIONS.md` (context, decision, alternatives, consequences).

## Learning mode (the author is still learning Java/Spring/Docker)
- The author must be able to defend every line in an interview, so code is only useful if it is understood.
- Before coding a slice: explain in 3–5 bullets what will be built, which Java/Spring/Docker concepts it uses, and why this design.
- After each slice: append to `docs/LEARNING.md` → concepts used (e.g. virtual threads, `@Transactional`, Docker cgroups), 2–3 likely interview questions with short answers, and links to official docs.
- Prefer plain, idiomatic Spring over clever abstractions. No framework magic the author hasn't had explained.

## Hard bans (vibe-code slop)
- No placeholder/fake implementations in `src/main` (`// TODO implement`, returning hardcoded data, `throw new UnsupportedOperationException()` stubs left behind). If something is deferred, it is tracked in `DECISIONS.md` or an issue, and the code path fails loudly.
- No swallowed exceptions (`catch (Exception e) {}` / log-and-continue without a reason). Use typed exceptions and a global `@ControllerAdvice`.
- No `catch (Exception e)` unless at a top-level boundary, with a comment why.
- No secrets, tokens, or URLs with credentials in code, tests, or logs. Config via env vars / `application.yml` placeholders.
- No new dependencies without stating why in the commit message. No unused dependencies, imports, or dead code.
- No editing tests to make them pass unless the test is provably wrong — explain why in the commit.
- No `@Disabled` tests, no `Thread.sleep` in tests (use Awaitility/latches).
- No field injection (`@Autowired` on fields). Constructor injection, `final` fields, records for DTOs.
- No business logic in controllers. Layers: `api` → `service` → `domain` / `infra` (enforced by ArchUnit).
- No god classes: > ~300 lines or > ~7 dependencies means split.
- Frontend: no `any`, no unhandled promises, no inline magic strings for API routes, no copy-pasted components.

## Security invariants (never weaken; tests must exist for each)
- Sandbox containers: `--network=none`, memory + swap cap, `--cpus`, `--pids-limit`, `--cap-drop=ALL`, `no-new-privileges`, non-root UID 1000, 60s timeout, `autoheal=true` label (PRD §6.1).
- Test Integrity Policy (FR-3.4) is enforced in code and covered by tests for every rule.
- Agent file tools are path-traversal safe (reject `..`, absolute paths, symlinks escaping the workspace).
- Webhook HMAC uses constant-time comparison; reject missing/invalid signatures.
- Secrets are never passed into sandbox containers.

## Quality gates (CI must enforce)
- `mvn -B verify` green: Spotless (google-java-format), Error Prone, JaCoCo ≥ 80% line coverage on `service`/`domain`, ArchUnit rules.
- Frontend: `tsc --noEmit`, ESLint (no warnings), Playwright smoke test.
- Conventional commits, one logical change per commit.
