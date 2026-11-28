# Current Working Memory

- **Current Phase:** Live E2E Verified & Zero-Placeholder Complete → Ready for Production Deployment (Azure for Students VM + Vercel, or Vercel + Cloudflare Tunnel) & Eval Run
- **Current Branch:** `main` (synced with `origin/main`)
- **Completed Work:**
  - PRD v1.1 (`PRD.md`), engineering rules (`AGENTS.md`), ADRs (`DECISIONS.md` ADR-001..005), learning log (`docs/LEARNING.md` Slices 1–21), portfolio `README.md`, single-command `./autoheal` dev launcher, and persistent memory.
  - **Weeks 1–5 (Slices 1–22):** Full backend (`130/130` tests, Spotless `166/166`, Error Prone `0` warnings, ArchUnit `4/4`, JaCoCo ≥80%), Next.js 15 Live Playground + Benchmarks UI (`2/2` Playwright E2E, `tsc --noEmit`, ESLint `0` warnings), hardened Docker sandbox (`autoheal-maven-runner:java21`), GitHub webhook, `pgvector` RAG, Draft PR wiring, Benchmarks API, Daily Demo Hygiene, and Evaluation Harness CLI.
  - **Live End-to-End Verification & Hardening (2026-11-28):**
    - Published real public demo repo `https://github.com/Bramhaaa/autoheal-demo-service` with `main` + all 4 failing `scenario/*` branches.
    - Added `./autoheal` single-command launcher (`up`/`down`/`restart`/`status`/`logs`, `a0f924e`) and fixed local `docker-compose.yml` `DATABASE_PASSWORD` + `.env.example` cron quoting (`478b075`).
    - Verified full LIVE healing E2E on `payment-npe-01`: healed on **Attempt 1 in 26.4s** for **`$0.00106`**, 82.0% log distillation, opened real GitHub Draft PR `https://github.com/Bramhaaa/autoheal-demo-service/pull/1`, unlocked Replay Mode.
    - Fixed live Gemini issues discovered during E2E (`ebbabb6`): switched `GeminiEmbeddingClient` to `gemini-embedding-001` (`768`-dim, `x-goog-api-key` header), added `Role.ASSISTANT` alternating `model` turns + step logging in `AgenticHealingService`, added 45s read timeout in `GeminiLlmClient`, and replaced `eval-corpus/bugs.example.json` with real `eval-corpus/bugs.json` (0 placeholders across repo).
- **Next Recommended Task:**
  - Deploy backend + Postgres + sandbox on **Azure for Students** Ubuntu VM (`docker-compose.prod.yml` + DuckDNS + Caddy auto-HTTPS, zero credit card required) and deploy `frontend/` to **Vercel**, then run `autoheal-eval` against `eval-corpus/bugs.json` (`PRD §7`, `§8`).
- **Known Minor Gaps (non-blocking):**
  - `DockerSandboxRunner.sweepExpiredContainers(Duration)` has unit coverage on `SandboxSweeperService` (mocked runner) but no dedicated Docker integration test yet.
  - Private-repo cloning via `JGitWorkspaceManager` assumes public HTTPS URLs (would need `UsernamePasswordCredentialsProvider` with `GITHUB_TOKEN` for private repos).
- **Blockers:** None (Oracle Cloud requires a credit card; use Azure for Students VM or Vercel + Cloudflare Tunnel instead).
