# Product Requirements Document (PRD): AutoHeal — Self-Healing CI/CD & Live Incident Playground

**Version:** 1.0

**Target Timeline:** 5 Weeks (Nov 23 – Dec 28)

**Primary Tech Stack:** Java 21, Spring Boot 3, Docker Engine, PostgreSQL (`pgvector`), Redis, Next.js (App Router)

**Deployment Target:** Vercel (Frontend) + AWS EC2 / Oracle Cloud VM (Spring Boot + Docker) + Supabase/Neon (PostgreSQL)

---

## 1. Executive Summary & Product Vision

### 1.1 Problem Statement

When a CI/CD pipeline fails or a production microservice throws a critical exception, engineers spend 20–60 minutes manually correlating stack traces, recent Git commits, and historical postmortems before writing and verifying a patch. Existing LLM-based code assistants often hallucinate fixes because they lack deterministic runtime verification.

### 1.2 Solution

**AutoHeal** is an autonomous incident triage and self-healing CI/CD system built in **Java 21 and Spring Boot 3**. It ingests failure webhooks, distills noisy stack traces into focused context, uses an LLM agent with tool-calling to propose a code patch and unit test, **deterministically verifies the patch inside an isolated, ephemeral Docker sandbox**, and opens a **GitHub Draft Pull Request** only after the test suite passes.

To eliminate setup friction for reviewers and recruiters, AutoHeal exposes a zero-setup **Live Incident Playground** web interface. Any visitor can trigger pre-configured production failures with one click, watch the live Server-Sent Events (SSE) execution pipeline and Docker test output transition from `RED` to `GREEN`, inspect the generated diff, and click through to the live Draft PR on GitHub.

### 1.3 Goals vs. Non-Goals

| Goals (In Scope) | Non-Goals (Out of Scope) |
| --- | --- |
| End-to-end automated triage, patching, Docker verification, and GitHub Draft PR creation. | Auto-merging PRs into `main` without human approval. |
| Zero-setup web playground with 4 pre-loaded real-world Java/Spring bug scenarios. | Supporting arbitrary user-connected private GitHub repositories in v1. |
| Strict sandbox isolation (CPU, memory, network disabled) for running untrusted AI patches. | Multi-language build runners (v1 targets Java/Maven; Python/Node are future extensions). |
| Quantitative evaluation harness benchmarking 25–30 real bugs for verifiable resume metrics. | Full enterprise SSO/OAuth multi-tenant billing. |

---

## 2. System Architecture & Tech Stack

### 2.1 High-Level Execution Flow

1. **Trigger:** Either a real GitHub Actions failure webhook OR a visitor clicking **"Simulate Crash"** in the Next.js Playground hits the Spring Boot API.
2. **Async Queueing:** Spring Boot creates an `IncidentRun` record (`QUEUED`), returns a `runId` immediately, and dispatches the job to a virtual-thread executor (or Redis-backed worker queue).
3. **Context Extraction:** The **Log Pre-Processor** strips boilerplate Maven/JVM noise, extracts the root exception, failing file/line numbers, recent Git commit diffs, and queries `pgvector` for similar past incidents.
4. **Agentic Patch Loop (Max 3 Retries):**
* The **Triage Agent** (LLM via structured tool-calling) inspects target files and generates a unified diff patch + a new JUnit 5 regression test.
* The **Docker Sandbox Runner** clones the target repo branch into an ephemeral container, applies the patch, and runs `mvn test`.
* If `mvn test` fails, the compiler/test error is fed back to the agent for the next retry iteration.


5. **Verification & Handoff:** Once `mvn test` exits with code `0`, Spring Boot pushes the fix branch to GitHub and opens a **Draft Pull Request** containing the root-cause analysis, test output, and latency/token metrics.
6. **Live Streaming:** Every state transition and Docker stdout line is streamed in real time to the Next.js frontend via **Server-Sent Events (SSE)**.

### 2.2 Technology Stack

| Layer | Technology | Rationale |
| --- | --- | --- |
| **Backend Core** | Java 21 (Virtual Threads), Spring Boot 3.3+ | High concurrency for I/O-heavy LLM/GitHub/Docker calls; aligns with enterprise backend standards. |
| **Container Runtime** | Docker Engine + `docker-java` SDK | Programmatic creation, log streaming, resource capping, and teardown of ephemeral sandboxes. |
| **LLM Integration** | Gemini API / OpenAI API (via Spring AI or WebClient) | Structured JSON output & tool-calling for deterministic patch generation. |
| **Database & RAG** | PostgreSQL 16 + `pgvector` (Supabase / Neon) | Stores incident runs, step logs, evaluation metrics, and vector embeddings of past postmortems. |
| **Cache & Rate Limiting** | Redis (Upstash or local container) | Sliding-window IP rate limiting, SSE pub/sub, and caching replayable runs. |
| **Frontend Playground** | Next.js (App Router), Tailwind CSS, Monaco/Diff Viewer | Deployed on Vercel; renders live terminal logs, state pipeline, and side-by-side code diffs. |
| **Infrastructure** | AWS EC2 (`t3.medium` or `t3.large`) / Oracle Cloud VM | Full root access for Docker Engine without serverless cold starts or Docker-in-Docker limits. |

---

## 3. Functional Requirements

### 3.1 Live Incident Playground (Frontend — Next.js)

* **FR-1.1 Scenario Selector:** Display 4 pre-loaded, realistic Java/Spring Boot bug scenarios stored in a public demo GitHub repository (`autoheal-demo-service`):
1. *Payment Service `NullPointerException*` (missing null check on optional discount payload).
2. *Off-by-One Pagination / Pricing Bug* (boundary condition failure in unit test).
3. *Concurrency Race Condition* (unsynchronized inventory decrement under multi-threaded test).
4. *SQL / Repository Query Typo* (broken JPQL query causing runtime failure).


* **FR-1.2 Live vs. Replay Mode:**
* **Live Run:** Triggers the real backend pipeline (rate-limited per IP).
* **Instant Replay Mode:** Streams a cached, real historical run at 2x speed with zero LLM cost—ensuring visitors can always experience the full flow even if rate limits are hit.


* **FR-1.3 Real-Time Pipeline Visualizer:** A 5-stage progress tracker (`Webhook Received` -> `Log Distilled` -> `Agent Patching` -> `Docker Sandbox Verification` -> `Draft PR Opened`) updated via SSE.
* **FR-1.4 Live Terminal & Diff Viewer:**
* Streaming terminal window showing real `mvn test` output turning from `BUILD FAILURE` (red) to `BUILD SUCCESS` (green).
* Side-by-side syntax-highlighted code diff of the AI's patch and generated JUnit test.
* Direct clickable link to the newly created GitHub Draft PR.



### 3.2 Log Pre-Processor & Context Builder (Backend)

* **FR-2.1 Noise Reduction:** Parse raw Maven/Java stack traces (up to 500KB) and strip framework internals (`org.springframework.*`, `java.base.*`, `jdk.internal.*`), retaining only application-package frames, root cause messages, and the failing test assertion. Target: reduce token payload by at least 75%.
* **FR-2.2 Git Diff Retrieval:** Fetch the last 3 commits and modified file contents from the target branch via GitHub REST API.
* **FR-2.3 Historical RAG Lookup:** Embed the distilled error signature and query PostgreSQL `pgvector` for the top 2 most similar resolved past incidents to include as few-shot context.

### 3.3 Agentic Triage & Patch Loop (Backend)

* **FR-3.1 Structured Tool-Calling:** Provide the LLM with strictly typed tools:
* `read_file(path, start_line, end_line)`
* `search_codebase(query)`
* `propose_patch(target_file, unified_diff, unit_test_file, unit_test_code, root_cause_summary)`


* **FR-3.2 Bounded Retry Loop:** Maximum of **3 attempts** per incident. If attempt `N` fails compilation or unit tests in Docker, append the exact `mvn test` failure snippet to the conversation history and prompt the agent to self-correct.
* **FR-3.3 Token & Latency Accounting:** Record prompt tokens, completion tokens, estimated USD cost, and wall-clock duration for every LLM call.

### 3.4 Ephemeral Docker Sandbox Runner (Backend)

* **FR-4.1 Base Image Optimization:** Use a pre-warmed custom Docker image (`autoheal-maven-runner:java21`) with Maven dependencies pre-cached in `/root/.m2` so `mvn test` executes in 5–12 seconds instead of downloading the internet on every run.
* **FR-4.2 Patch Application:** Mount or copy the target repo into the container, apply the unified diff (`git apply`), and execute `mvn test -o` (offline mode).
* **FR-4.3 Guaranteed Cleanup:** Use a `try/finally` block + a scheduled sweeper job to forcefully kill and remove any container running longer than 60 seconds.

### 3.5 GitHub Draft PR Integration (Backend)

* **FR-5.1 Branch & PR Creation:** On verified green build, create a branch `autoheal/fix-{scenario}-{shortUuid}`, commit the patch + regression test, and open a **Draft PR** via GitHub API.
* **FR-5.2 PR Body Template:** Automatically populate the PR description with:
* Root Cause Analysis
* Summary of Changes
* Verification Evidence (Docker exit code, test count, execution time)
* Agent Metrics (Retries used, total latency, token cost)


* **FR-5.3 Demo Repo Hygiene:** A daily scheduled cron job closes and deletes stale demo PRs/branches older than 24 hours to keep the public demo repo clean.

---

## 4. API Contracts & SSE Specification

### 4.1 REST Endpoints

```http
POST /api/v1/incidents/simulate
Content-Type: application/json

{
  "scenarioId": "payment-npe-01",
  "mode": "LIVE" // or "REPLAY"
}

```

**Response (`202 Accepted`):**

```json
{
  "runId": "run_9f8a7b6c",
  "status": "QUEUED",
  "streamUrl": "/api/v1/incidents/run_9f8a7b6c/stream"
}

```

```http
POST /api/v1/webhooks/github
X-Hub-Signature-256: sha256=...

```

*Validates HMAC-SHA256 webhook signature from GitHub Actions `workflow_run` failure events and triggers the same internal pipeline.*

```http
GET /api/v1/incidents/{runId}

```

*Returns full structured JSON of a completed or in-progress run (status, diff, root cause, PR URL, metrics).*

```http
GET /api/v1/benchmarks/summary

```

*Returns aggregate stats from the evaluation harness (pass rate, avg resolution time, avg token cost) to display on the Playground's "Benchmarks" tab.*

### 4.2 Server-Sent Events (SSE) Stream Contract

**Endpoint:** `GET /api/v1/incidents/{runId}/stream` (`text/event-stream`)

Each event payload follows a strict JSON envelope:

```json
{
  "eventId": 14,
  "timestamp": "2026-12-01T10:15:32Z",
  "stage": "SANDBOX_VERIFY",
  "type": "LOG_LINE", // STAGE_UPDATE | LOG_LINE | PATCH_PROPOSED | COMPLETED | FAILED
  "payload": {
    "message": "[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0",
    "attempt": 1
  }
}

```

---

## 5. Database Schema (PostgreSQL + `pgvector`)

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE incident_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scenario_id VARCHAR(64) NOT NULL,
    repo_name VARCHAR(128) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL, -- QUEUED, TRIAGING, VERIFYING, SUCCEEDED, FAILED
    attempts_used INT DEFAULT 0,
    root_cause_summary TEXT,
    generated_diff TEXT,
    pr_url VARCHAR(255),
    total_tokens INT DEFAULT 0,
    estimated_cost_usd NUMERIC(10, 6) DEFAULT 0,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE incident_events (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID REFERENCES incident_runs(id) ON DELETE CASCADE,
    stage VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE postmortem_embeddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    error_signature TEXT NOT NULL,
    resolution_summary TEXT NOT NULL,
    embedding vector(768), -- Match chosen embedding model dimensions
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_postmortem_embedding 
ON postmortem_embeddings USING ivfflat (embedding vector_cosine_ops);

```

### Redis Key Design

* `ratelimit:ip:{ipAddress}` — Sliding window counter (e.g., max 3 live runs per hour per IP).
* `replay:scenario:{scenarioId}` — Cached JSON array of `incident_events` for zero-cost instant replay.

---

## 6. Non-Functional Requirements & Security Guardrails

### 6.1 Sandbox Security (Critical)

Running LLM-generated code is equivalent to running untrusted code. Every Docker container spawned by `docker-java` **must** enforce:

* `--network=none` (zero internet or internal metadata-service access).
* `--memory=512m` and `--cpus=1.0` (prevents fork bombs and OOM crashes on the host VM).
* `--read-only` root filesystem with a small writable `tmpfs` mount for `/workspace` and `/tmp`.
* Non-root container user (`USER 1000:1000`).
* Hard execution timeout of **60 seconds**.

### 6.2 Cost & Abuse Protection

* Strict IP-based rate limiting via Redis (`429 Too Many Requests` returns a prompt suggesting **Replay Mode**).
* Global daily circuit breaker: if total daily LLM spend exceeds a configurable threshold (e.g., `$2.00/day`), automatically degrade all incoming Playground requests to **Replay Mode** with a transparent banner.

### 6.3 Performance Targets

* **SSE First-Byte Latency:** Less than 300ms from clicking "Simulate Crash".
* **End-to-End Resolution Time:** Under 45 seconds for a 1-attempt fix (pre-warmed Maven cache is mandatory to hit this).
* **Concurrent Runs:** Support at least 3 concurrent Docker sandboxes on a 2-vCPU / 4GB RAM VM; queue additional requests gracefully.

---

## 7. Evaluation Harness & Resume Metrics

To prove engineering rigor (and generate defensible numbers for FAANG/FDE interviews), build a standalone CLI/JUnit runner (`autoheal-eval`) that executes the agent against **25–30 curated Java bug commits** (e.g., a subset of Defects4J or custom Spring Boot bug branches) and outputs a Markdown benchmark report:

| Metric | Definition | Target |
| --- | --- | --- |
| **Pass@1 Rate** | Percentage of bugs fixed and passing tests on the first attempt. | Greater than 60% |
| **Pass@3 Rate** | Percentage of bugs fixed within the 3-attempt self-correction loop. | Greater than 75% |
| **Log Compression Ratio** | Reduction in tokens after the Log Pre-Processor vs. raw stack trace. | Greater than 75% |
| **Mean Time to Verified Patch** | Average wall-clock time from webhook to green Docker build. | Less than 45s |
| **Mean Cost per Fix** | Average LLM API cost per resolved incident. | Less than $0.03 |

---

## 8. 5-Week Implementation Roadmap

* **Week 1: Foundation & Docker Sandbox**
* Set up the public `autoheal-demo-service` repo with 4 intentional bug branches.
* Build the pre-warmed Maven Docker image and the Java `DockerSandboxService` with strict security flags and live stdout streaming.


* **Week 2: Log Pre-Processor & Agent Loop**
* Implement the stack trace distiller, GitHub API client (read files, fetch diffs, open Draft PRs), and the 3-retry LLM tool-calling loop.


* **Week 3: SSE Streaming, Persistence & Guardrails**
* Wire up PostgreSQL + `pgvector` RAG lookup, Redis IP rate limiting, Replay Mode caching, and the Spring Boot SSE endpoint.


* **Week 4: Live Incident Playground (Next.js) & Deployment**
* Build the Next.js UI (Scenario cards, 5-stage visualizer, live terminal, side-by-side diff viewer).
* Deploy backend + Docker on EC2/Oracle VM (with Nginx + Let's Encrypt HTTPS) and frontend on Vercel.


* **Week 5: Evaluation Harness, Load Testing & Polish**
* Run the 25–30 bug benchmark suite, publish the results table and architecture diagram in the README, and record a 90-second demo video.



---