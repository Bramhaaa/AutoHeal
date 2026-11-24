---
name: session-shutdown
description: End an AutoHeal work session cleanly by verifying git status/diff, updating only affected persistent memory files (.agents/memory/current.md, index.md if structure changed, today's session log), and producing a clear handoff summary for the next session. Use when the user says "shutdown", "end session", "wrap up", or "hand off".
---

# Session Shutdown Workflow

Capture verified session outcomes so the next session can resume cleanly in seconds.

## Steps
1. Inspect actual repository state: `git status --short && git log --oneline -5` (and `git diff --stat` if uncommitted changes exist).
2. Read `.agents/memory/current.md`, `.agents/memory/index.md`, and today's `.agents/memory/sessions/YYYY-MM-DD.md` (if present).
3. Determine which memory files actually changed:
   - Always update `.agents/memory/current.md` (branch, HEAD, completed slices, next task, blockers/gaps).
   - Update `.agents/memory/index.md` **only** if modules/features/key files were added, moved, or removed (keep under ~150 lines).
   - Update `.agents/memory/architecture.md` **only** if architecture/layers/pipeline changed.
   - Check `DECISIONS.md` and `docs/LEARNING.md` — note if any completed slice is missing an ADR or learning entry.
4. Append a concise summary to `.agents/memory/sessions/YYYY-MM-DD.md` (create from `.agents/templates/session.md` if needed; never overwrite earlier entries).
5. Output a brief end-of-session handoff:
   - What was completed & committed (or uncommitted warning)
   - Memory files updated
   - Exact next task for the next session (with FR IDs)

## Rules
- Verify everything against the actual repository — never rely solely on chat memory.
- Do not rewrite unchanged memory files.
- Do not make unrelated code edits during shutdown.
