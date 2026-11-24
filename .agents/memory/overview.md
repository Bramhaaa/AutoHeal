# AutoHeal — Overview

**What it is:** Autonomous CI/CD incident triage and self-healing system in **Java 21 + Spring Boot 3.4**. Ingests failure webhooks or Playground triggers, distills logs, uses an LLM agent to propose search/replace patches, deterministically verifies patches inside a hardened `--network=none` Docker sandbox against the original failing test (Test Integrity Policy), and opens a GitHub Draft PR.

**Core Stack:**
- Backend: Java 21 (Virtual Threads), Spring Boot 3.4.3, Maven, `docker-java` (zerodep), JGit, PostgreSQL 16 + `pgvector` (Week 3), Bucket4j.
- Frontend (Week 4): Next.js (App Router), TypeScript (strict), Tailwind, Monaco Diff Editor.
- Sandbox: Custom `autoheal-maven-runner:java21` image with `/opt/m2` warmed via real `mvn test`.

**Canonical Docs:**
- Requirements & roadmap: `PRD.md` (v1.1)
- Architecture decisions (ADRs): `DECISIONS.md`
- Engineering & learning rules: `AGENTS.md`
- Concept explanations & interview Q&As: `docs/LEARNING.md`
