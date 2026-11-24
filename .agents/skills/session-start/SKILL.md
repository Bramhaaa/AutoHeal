---
name: session-start
description: Start a new AutoHeal work session by loading persistent project memory (.agents/memory/), checking git status, summarizing current state and next recommended task, and waiting for user direction. Use when the user says "start", "start session", "resume", or begins a fresh conversation.
---

# Session Start Workflow

Restore working context quickly with minimal repository scanning.

## Steps
1. Read persistent memory in order (batch in parallel):
   - `.agents/memory/overview.md`
   - `.agents/memory/architecture.md`
   - `.agents/memory/index.md`
   - `.agents/memory/current.md`
   - `DECISIONS.md`
   - The most recent file in `.agents/memory/sessions/`
2. Run a quick read-only check: `git status --short && git log --oneline -5`.
3. Output a concise startup brief:
   - Current phase, branch, and last commit
   - What was completed last session
   - Any blockers or open gaps
   - Next recommended slice/task (with FR IDs)
4. Wait for the user's instruction unless they already gave a concrete task in the same message.

## Rules
- Repository code is always the ultimate source of truth; if memory disagrees with `git status` or source code, note the discrepancy.
- Do not modify memory files during start unless explicitly asked.
- Do not scan the whole repository during start — use `.agents/memory/index.md` to locate files later when needed.
