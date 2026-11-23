---
name: autoheal-quality-gate
description: Run before declaring any AutoHeal slice/FR done or opening a PR. Executes build/test/lint gates, audits the diff for vibe-coded slop and security-invariant regressions, and reports evidence. Use whenever finishing a task, before committing, or when asked to "review", "check", or "verify" AutoHeal code.
---

# AutoHeal Quality Gate

Goal: no slice is "done" unless it is built, tested, reviewed against the PRD, and free of AI slop. Report evidence (command + output), never assertions.

## Step 1 — Run the gates (show output)
Run what exists in the repo; if a gate is not set up yet, say so explicitly rather than skipping silently.
- Backend: `mvn -B -q verify` (Spotless, Error Prone, tests, JaCoCo, ArchUnit).
- Frontend (if touched): `npm run typecheck && npm run lint && npx playwright test --project=smoke`.
- If sandbox code changed: run the sandbox integration tests against a real Docker daemon.

Any red gate → stop, use the `systematic-debugging` skill, fix, re-run.

## Step 2 — Requirement trace
- List the FR IDs this change implements (from `PRD.md`).
- For each acceptance criterion, point to the test that proves it. Missing test → write it (use `test-driven-development`).

## Step 3 — Slop audit of the diff (`git diff main...HEAD`)
Flag and fix each occurrence:
- [ ] Stubs/placeholders: `TODO`, `FIXME`, `not implemented`, hardcoded fake return values, `UnsupportedOperationException` in `src/main`.
- [ ] Swallowed or overly broad exceptions; logging secrets or full payloads.
- [ ] Tests that assert nothing meaningful (only `assertNotNull`, mocking the unit under test, no failure-path tests).
- [ ] Tests modified/weakened to pass without a written justification.
- [ ] Duplicated logic, dead code, unused imports/deps, commented-out code.
- [ ] Hallucinated APIs: verify every non-trivial library call exists in the version pinned in `pom.xml` / `package.json` (check docs or source, don't guess).
- [ ] Field injection, logic in controllers, layer violations, god classes.
- [ ] Magic numbers/strings that belong in config (timeouts, limits, model names, prices).
- [ ] Frontend: `any`, unhandled promises, missing loading/error/empty states.

## Step 4 — Security invariants (see AGENTS.md)
If the diff touches sandbox, agent tools, patch policy, webhook, or config, confirm each invariant still holds **and is covered by a test**:
- Sandbox flags (network none, mem/swap, cpus, pids, cap-drop, no-new-privileges, UID 1000, timeout, label).
- Test Integrity Policy rules (existing tests untouched, only `src/main/**` + new tests, no skip annotations, oracle test passes, test count not reduced).
- Path traversal protection in `read_file` / `list_files` / `search_code`.
- HMAC constant-time verification; no secrets in containers/logs.

## Step 5 — Explainability check
For each new class/method, could the author explain *why* it exists and its trade-offs in an interview? If a design choice is non-obvious, add a `DECISIONS.md` entry.

## Step 6 — Report
Output a short report:
```
Gates: backend ✅/❌ (cmd + key output), frontend ✅/❌/n.a.
FRs covered: FR-x.y → TestClass#method ...
Slop findings: fixed N / remaining (with reason)
Security invariants: ✅ / ⚠️ details
ADRs added: ...
```
Only after an all-green report may the work be called done.
