---
name: session-checkpoint
description: Save mid-session progress into .agents/memory/current.md and append a brief checkpoint note to today's session log without closing the session. Use when the user says "checkpoint", finishes a slice mid-session, or before switching tasks.
---

# Session Checkpoint Workflow

Preserve verified mid-session progress without rewriting broader memory.

## Steps
1. Check `git status --short && git log --oneline -5` to confirm actual repo state.
2. Read `.agents/memory/current.md` and today's `.agents/memory/sessions/YYYY-MM-DD.md` (create from `.agents/templates/session.md` if it does not exist).
3. Update `.agents/memory/current.md` with completed work, current branch/HEAD, blockers, and immediate next task.
4. Append a short timestamped checkpoint bullet to today's session log.
5. Leave `overview.md` and `architecture.md` untouched unless a verified structural change occurred.

## Rules
- Never overwrite historical session logs.
- Keep notes concise and grounded in verified commits/files.
