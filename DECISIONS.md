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
