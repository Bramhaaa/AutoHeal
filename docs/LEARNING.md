# AutoHeal Learning Log

This file grows after every slice. By the end of the project, it doubles as your Java, Spring Boot, and Docker interview prep guide.

---

## Slice 1: Project Skeleton & Automated Build Quality Gates

### What was built
- Initialized the Git repository and `backend/` Maven project targeting Java 21 with Spring Boot 3.4.
- Configured four automated build gates in `backend/pom.xml` so `mvn -B verify` catches issues automatically:
  - **Spotless (`google-java-format`)**: enforces consistent formatting and removes unused imports.
  - **Error Prone**: Google's static analysis compiler plugin that catches subtle Java bugs at compile time (e.g., `equals` on incompatible types, ignored return values, bad format strings).
  - **JaCoCo**: measures test coverage and fails the build if line coverage in `com.autoheal.service.*` or `com.autoheal.domain.*` drops below 80%.
  - **ArchUnit (`ArchitectureTest`)**: unit tests that enforce package boundaries (`api` → `service` → `domain` / `infra`) and ban field injection (`@Autowired` on fields).
- Enabled Java 21 **Virtual Threads** in `application.yml` (`spring.threads.virtual.enabled: true`).

### Key Concepts Used
- **Maven build lifecycle (`compile` → `test` → `verify`)**: Plugins hook into lifecycle phases. Binding Spotless and JaCoCo checks to `verify` means a single command (`mvn -B verify`) runs compilation, static analysis, tests, formatting, and coverage.
- **Constructor Injection vs. Field Injection**: In Spring, injecting dependencies via a constructor with `private final` fields guarantees immutability, makes dependencies explicit, and lets you unit-test classes with plain `new MyService(...)` without starting Spring.
- **Java 21 Virtual Threads (Project Loom)**: Lightweight threads managed by the JVM rather than the OS. Because AutoHeal spends most of its time waiting on I/O (Docker, GitHub API, LLM HTTP calls, Postgres), virtual threads let us handle concurrent runs with simple synchronous code instead of complex reactive callbacks.
- **`--release 21` and `.mvn/jvm.config`**: Compiling with `--release 21` ensures bytecode and standard-library calls are strictly Java 21 even when your machine runs JDK 22. `.mvn/jvm.config` passes `--add-exports` flags so Error Prone can inspect `javac` internals cleanly.

### Likely Interview Questions
1. **Why prefer constructor injection over `@Autowired` field injection in Spring?**
   - *Answer:* Constructor injection lets you make fields `final` (immutable), guarantees the object is never in a half-initialized state, exposes hidden coupling (too many constructor args = god class), and makes plain unit tests trivial without reflection or a Spring context.
2. **Why did you enable Java 21 Virtual Threads instead of using Spring WebFlux (reactive)?**
   - *Answer:* AutoHeal's workload is I/O-bound (waiting on Docker, GitHub, and LLM APIs). Virtual threads give high concurrency with blocking I/O at minimal memory overhead while keeping stack traces readable and code simple—without the cognitive overhead of reactive chains (`Mono`/`Flux`).
3. **How do you prevent architectural drift as a codebase grows?**
   - *Answer:* Enforce rules in CI, not just in docs. ArchUnit runs as a JUnit test on every build, so if someone imports an `infra` class inside a controller (`api`) or puts `@Autowired` on a field, the build immediately fails.

### Official Docs
- [Spring Boot Reference — Virtual Threads](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.virtual-threads)
- [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)
- [Google Error Prone](https://errorprone.info/)
- [JaCoCo Maven Plugin](https://www.jacoco.org/jacoco/trunk/doc/maven.html)

---

## Slice 2: Demo Service & 4 Deterministic Bug Scenarios (`FR-1.1`)

### What was built
- Created `demo-service/` (`autoheal-demo-service`), a Spring Boot 3.4 / Java 21 Maven project with 4 realistic services and JUnit 5 tests on a green baseline:
  1. `PaymentService` (`payment-npe-01`): `NullPointerException` when an optional `Discount` record is `null`.
  2. `PaginationService` (`pagination-off-by-one-02`): off-by-one upper bound in `List.subList`.
  3. `InventoryService` (`inventory-race-03`): lost-update race condition on `ConcurrentHashMap` when `getOrDefault` + `put` is used instead of atomic `compute(...)`.
  4. `CustomerOrderRepository` (`jpql-typo-04`): broken JPQL field name (`o.status` instead of `o.orderStatus`), tested via `@DataJpaTest` + H2 in-memory DB (no network needed).
- Stored the 4 bug patches in `demo-service/scenarios/*.patch` plus `demo-service/scripts/create-scenario-branches.sh` to generate a standalone Git repo with `main` (green) and `scenario/*` branches.
- Verified offline (`mvn -B -o test`) that all 4 bug branches fail, and `inventory-race-03` fails 20/20 times when buggy and passes 20/20 times when fixed.

### Key Concepts Used
- **Java `record` (`Discount`, `PaymentRequest`)**: Concise immutable data carriers introduced in Java 16+. Automatically generates constructor, getters (`request.subtotal()`), `equals`, `hashCode`, and `toString`.
- **Compound actions on `ConcurrentHashMap`**: `ConcurrentHashMap` makes individual operations (`get`, `put`) thread-safe, **not** sequences of operations (`get` → compute → `put`). Two threads can read the same stock count and overwrite each other (lost update). `stockBySku.compute(sku, (k, v) -> ...)` executes the read-modify-write atomically per key bucket.
- **`CountDownLatch` for deterministic concurrency tests**: Instead of `Thread.sleep`, two latches (`ready` and `start`) force all 8 worker threads to start decrementing at the exact same instant, maximizing contention so the race condition reproduces 20/20 times.
- **`@DataJpaTest` + H2**: A Spring Boot *test slice* that starts only JPA/Hibernate and an embedded H2 database—much faster than `@SpringBootTest` and works completely offline inside a `--network=none` Docker sandbox.
- **Pinning Maven plugins & `argLine`**: Explicitly pinning `maven-compiler-plugin` and `maven-surefire-plugin` versions and setting `-Xmx256m` prevents Maven from trying to resolve plugin metadata online and keeps the forked test JVM inside the sandbox's 768 MB memory cap.

### Likely Interview Questions
1. **Isn't `ConcurrentHashMap` thread-safe? Why did `inventory-race-03` still have a race condition?**
   - *Answer:* Each individual method (`get`, `put`) is thread-safe, so the map's internal structure won't corrupt. However, a compound read-modify-write (`get` → subtract → `put`) is not atomic: two threads can read `100`, both compute `99`, and both write `99`, losing one decrement. Using `compute()` (or `merge()` / `AtomicInteger`) makes the entire update atomic per key.
2. **How do you write a non-flaky test for a race condition?**
   - *Answer:* Avoid `Thread.sleep`. Use `CountDownLatch` to synchronize thread start so all threads hit the critical section simultaneously, and run enough iterations (e.g., 8 threads × 10,000 decrements) so the buggy code fails deterministically (verified 20/20 runs) while the fixed code passes 20/20 runs.
3. **What is the difference between `@SpringBootTest` and `@DataJpaTest`?**
   - *Answer:* `@SpringBootTest` boots the entire application context. `@DataJpaTest` loads only the JPA layer (entities, repositories, Hibernate, and an embedded database like H2 by default) and rolls back each test transaction automatically, making repository tests fast and isolated.

### Official Docs
- [Java `ConcurrentHashMap.compute`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html#compute(K,java.util.function.BiFunction))
- [Java `CountDownLatch`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CountDownLatch.html)
- [Spring Boot Reference — `@DataJpaTest`](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.autoconfigured-spring-data-jpa)

---

## Slice 3: Pre-Warmed Runner Image & Hardened Docker Sandbox (`FR-4.1`, `FR-4.2`, `FR-4.3`, §6.1)

### What was built
- Built `sandbox/Dockerfile` (`autoheal-maven-runner:java21`): creates non-root UID 1000, warms `/opt/m2` by running a real `mvn test` on `demo-service` at image build time, and verified the Week-1 `--read-only` + `--network=none` spike (`mvn -o test` passed in 8.7s).
- Added `SandboxRequest`, `SandboxResult`, and `SandboxRunner` in `com.autoheal.domain` (hexagonal port: `service` depends on `domain`, `infra` implements `domain`).
- Built `SandboxContainerSpec`, `DockerSandboxClientFactory`, and `DockerSandboxRunner` in `com.autoheal.infra.docker` using `docker-java-core` + `docker-java-transport-zerodep`:
  - Enforces all §6.1 controls: `--network=none`, `768 MiB` memory + swap cap, `1.0` CPU, `256` PIDs limit, `--cap-drop=ALL`, `no-new-privileges`, `--read-only` rootfs + `tmpfs` `/tmp`, UID `1000:1000`, `autoheal=true` label, and no host secrets in env.
  - Streams stdout/stderr line-by-line via `ResultCallback`, enforces hard timeout, always force-removes containers in `finally`, and provides `sweepExpiredContainers(...)`.
- Verified with unit tests (`SandboxContainerSpecTest`, `SandboxDomainTest`) and real Docker integration tests (`DockerSandboxRunnerTest`: green build, failing build, 1s timeout kill, zero leaked containers).

### Key Concepts Used
- **Linux cgroups & namespaces in Docker**: `--memory`/`--memory-swap`, `--cpus` (`NanoCPUs`), and `--pids-limit` are enforced by kernel **cgroups** (preventing OOM and fork bombs). `--network=none` gives the container an isolated network namespace with only loopback, blocking internet and cloud metadata (`169.254.169.254`) access.
- **Linux Capabilities & `no-new-privileges`**: `--cap-drop=ALL` strips all kernel privileges (like `CAP_NET_RAW`, `CAP_SYS_ADMIN`). `no-new-privileges` blocks setuid binaries from gaining privileges even if present.
- **Read-only rootfs + `tmpfs`**: `--read-only` prevents untrusted code from modifying `/opt/m2` or system binaries; `tmpfs` at `/tmp` (`noexec,nosuid`) gives the JVM a small in-memory scratch space where binaries cannot be executed.
- **Hexagonal Architecture (Ports & Adapters)**: `SandboxRunner` lives in `com.autoheal.domain` with zero Docker imports. `DockerSandboxRunner` in `com.autoheal.infra.docker` implements it. ArchUnit enforces that `domain` never imports `infra`, so we can unit-test the agent loop later without Docker.

### Likely Interview Questions
1. **Why aren't `--memory` and `--cpus` enough to stop a fork bomb inside a Docker container?**
   - *Answer:* Even with a memory cap, spawning thousands of tiny processes can exhaust the host's PID table (`kernel.pid_max`) and starve other processes on the VM. Setting `--pids-limit=256` caps the total number of processes/threads in that container's cgroup.
2. **Why warm the Maven cache with a real `mvn test` instead of `mvn dependency:go-offline`?**
   - *Answer:* `dependency:go-offline` does not always resolve dynamically loaded plugins like Surefire's JUnit Platform provider or compiler annotation processors. Running a real `mvn test` during `docker build` guarantees every runtime and test plugin is cached in `/opt/m2` so `mvn -o test` succeeds with `--network=none`.
3. **Why did you put the `SandboxRunner` interface in `domain` and its Docker implementation in `infra`?**
   - *Answer:* Dependency inversion (hexagonal architecture). Business logic in `service` depends only on the `SandboxRunner` interface, not `docker-java`. That keeps domain logic portable, lets us swap runtimes (e.g. gVisor or remote runners), and makes unit tests fast and deterministic.

### Official Docs
- [Docker Engine Security](https://docs.docker.com/engine/security/)
- [Docker Resource Constraints (cgroups)](https://docs.docker.com/engine/containers/resource_constraints/)
- [docker-java GitHub](https://github.com/docker-java/docker-java)

---

## Slice 4: JGit Workspace Manager & Surefire XML Report Parser (End of Week 1)

### What was built
- **Surefire XML Report Parser** (`SurefireReportParser` in `com.autoheal.service` + `TestSuiteReport`/`TestCaseId` records in `com.autoheal.domain`):
  - Parses `target/surefire-reports/TEST-*.xml` to extract exact passing tests, failing/error tests, skipped count, and executed test count (the data needed by the Test Integrity Policy oracle in `FR-3.4`).
  - Hardened `DocumentBuilderFactory` against XXE (`disallow-doctype-decl`, external entities disabled).
- **JGit Host-Side Workspace Manager** (`JGitWorkspaceManager` in `com.autoheal.infra.git` + `WorkspaceManager`/`WorkspaceHandle` in `com.autoheal.domain`):
  - Clones a specific branch into an isolated per-run temp directory using JGit (no shelling out to `git`, works while the sandbox stays `--network=none`).
  - `WorkspaceHandle` implements `AutoCloseable`, so `try (WorkspaceHandle ws = ...)` guarantees workspace deletion even on exceptions.
  - `readRecentDiffs(...)` reads recent commit messages and unified diffs via JGit `DiffFormatter` (capped at 16 KB for `FR-2.2`).

### Key Concepts Used
- **XML External Entity (XXE) Prevention**: Surefire XML files are produced inside an untrusted sandbox running AI-generated code. By default, Java's `DocumentBuilderFactory` allows `<!DOCTYPE>` declarations that can read host files (`file:///etc/passwd`) or trigger SSRF. Setting `disallow-doctype-decl = true` blocks XXE completely.
- **`AutoCloseable` & try-with-resources**: Wrapping a temporary directory in an `AutoCloseable` handle (`WorkspaceHandle`) leverages Java's try-with-resources so cleanup runs deterministically when the block exits—just like closing a file or DB connection.
- **JGit (`Git.cloneRepository`, `DiffFormatter`)**: Pure-Java Git implementation. Avoids OS-level `ProcessBuilder("git", ...)` command injection risks and makes testing against temporary local Git repos fast and portable.

### Likely Interview Questions
1. **Why harden the XML parser when it only reads Maven Surefire reports?**
   - *Answer:* The Surefire XML is generated inside the sandbox where untrusted AI code ran—malicious or buggy test code could write a crafted XML file containing an XXE payload (`<!DOCTYPE ... SYSTEM "file:///etc/passwd">`). Disabling DTDs in `DocumentBuilderFactory` is a 4-line defense-in-depth safeguard.
2. **Why clone the repo with JGit on the host instead of cloning inside Docker or shelling out to `git`?**
   - *Answer:* The sandbox has `--network=none`, so it cannot clone. On the host, JGit avoids shelling out (no command-injection surface, no dependency on host `git` binary/version) and lets us compute commit diffs programmatically.
3. **Why does `WorkspaceHandle` implement `AutoCloseable`?**
   - *Answer:* So callers can use `try (var ws = workspaceManager.cloneBranch(...))` and guarantee temp directories are deleted even if an exception is thrown mid-pipeline.

### Official Docs
- [OWASP XXE Prevention Cheat Sheet (Java)](https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html#jaxp-documentbuilderfactory-saxparserfactory-and-dom4j)
- [Eclipse JGit Documentation](https://eclipse.dev/jgit/documentation/)
- [Java `AutoCloseable`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/AutoCloseable.html)

---

## Slice 5: Path-Safe Workspace Tools, Test Integrity Policy & Patch Applier (`FR-3.1`, `FR-3.4`, `ADR-001`, `ADR-003`)

### What was built
- **Path-Traversal Guard (`WorkspacePathResolver`) & Local Tools (`WorkspaceTools`)**:
  - Rejects `..`, absolute paths, NUL bytes, and symlinks escaping the workspace root (including non-existent targets checked via their nearest existing ancestor).
  - Exposes `listFiles(dir, glob)`, `readFile(path, startLine, endLine)` (capped at 400 lines), and `searchCode(query, glob)` (literal substring match, skips `.git/`, `target/`, `node_modules/`, binary files, and escaping symlinks).
- **Test Integrity Policy (`TestIntegrityPolicy`)**:
  - Pre-sandbox `validatePatch(...)`: requires at least one edit, restricts edits strictly to existing files under `src/main/**`, rejects tampering with existing tests / `pom.xml` / `.mvn/**` / `mvnw` / CI files, allows brand-new test files under `src/test/**` only if they don't overwrite existing tests or contain `@Disabled`, `@Ignore`, `assumeTrue(false)`, `assumeFalse(true)`, or `Assumptions.abort(...)`.
  - Post-sandbox `verifyOutcome(...)`: marks a run verified only when exit code is `0`, post-patch failures/errors are `0`, every originally failing Surefire oracle test now passes, and executed test count is `>=` baseline.
- **Search/Replace Patch Applier (`PatchApplier`)**:
  - Implements `ADR-001`: validates and stages all edits in memory first (exact-match once; 0 matches = not found, >1 matches = ambiguous) so a partial failure never leaves the workspace half-patched, then writes files and generates a canonical unified diff via `java-diff-utils`.

### Key Concepts Used
- **Canonical Path Resolution (`Path.normalize()` vs `Path.toRealPath()`)**: `normalize()` is purely lexical—it collapses `a/../b` without touching the filesystem, so it cannot detect symlinks. Calling `toRealPath()` resolves OS symlinks and guarantees the physical target stays inside `workspaceRoot.toRealPath()`.
- **Two-Phase Validate-Then-Write (Atomic Staging)**: Staging all search/replace edits in a `LinkedHashMap<Path, String>` before writing anything to disk guarantees all-or-nothing patch application without needing complex rollback code.
- **Literal Search vs. Regex (`String.indexOf` / `contains`)**: Using exact literal substring matching for both `PatchApplier` and `searchCode` avoids regex escaping bugs (`$`, `(`, `[`) and eliminates ReDoS (catastrophic backtracking) from untrusted LLM inputs.
- **Server-Side Unified Diff (`java-diff-utils`)**: LLMs frequently miscount unified diff hunk headers (`@@ -12,7 +12,9 @@`). Having the LLM submit exact search/replace blocks and computing the unified diff server-side removes that entire failure class.

### Likely Interview Questions
1. **Why isn't `Path.normalize().startsWith(root)` alone enough to prevent path traversal?**
   - *Answer:* `normalize()` only removes `..` lexically; it does not follow symbolic links. A symlink inside the workspace pointing to `/etc/passwd` passes `normalize().startsWith(root)`, so you must also resolve `toRealPath()` (or the nearest existing ancestor's `toRealPath()` for files not yet created) and check `startsWith(realRoot)`.
2. **How do you prevent an AI coding agent from "cheating" to make tests pass?**
   - *Answer:* Enforce a deterministic Test Integrity Policy in code before and after sandboxing: edits are restricted to `src/main/**`, existing tests and build files (`pom.xml`, `.mvn/**`, CI) are immutable, new tests cannot use `@Disabled`/`assumeTrue(false)`, and verification parses Surefire XML to confirm the exact originally failing test now passes and total executed tests did not drop.
3. **Why use search/replace blocks instead of asking the LLM for a `git diff`?**
   - *Answer:* LLMs are notoriously bad at line-number arithmetic and hunk headers in unified diffs, causing `git apply` failures. Exact-match search/replace blocks fail loudly with a clear message if missing or ambiguous, and the backend generates a guaranteed-valid unified diff with `java-diff-utils`.

### Official Docs
- [Java NIO `Path.toRealPath()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html#toRealPath(java.nio.file.LinkOption...))
- [OWASP Path Traversal](https://owasp.org/www-community/attacks/Path_Traversal)
- [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils)

---

## Slice 6: Log Distiller (`FR-2.1`)

### What was built
- **`DistilledLog` record** (`com.autoheal.domain`): immutable result holding the distilled summary, deduplicated `failingTests`, deduplicated `referencedFiles` (`.java` filenames extracted from stack frames and compiler errors for `FR-2.2` commit-diff lookup), raw/distilled lengths, and `reductionPercent()`.
- **`LogDistiller`** (`com.autoheal.service`):
  - Caps input at 500 KB (`500 * 1024` chars) to protect heap memory.
  - Strips Maven `[INFO]` chatter, download progress lines, Spring Boot banners, and framework stack frames (`org.springframework.*`, `java.base/*`, `jdk.internal.*`, `org.junit.*`, `sun.*`, `org.hibernate.*`, `org.mockito.*`, `org.apache.maven.*`), collapsing consecutive omitted frames into a single count marker.
  - Preserves the full `Caused by:` chain, application-package stack frames (collapsing repeated recursive frames), failing test headers + assertions, and `javac` compiler errors with file/line numbers.
  - Achieves ≥75% noise reduction on realistic Maven failure logs (verified in `LogDistillerTest`).

### Key Concepts Used
- **`String.lines()` vs `String.split("\r?\n")`**: Java 11+ `String.lines()` streams lines across `\n`, `\r\n`, and `\r` without compiling a regex or hitting `String.split`'s trailing-empty-string quirk (which Error Prone flags via `[StringSplitter]`).
- **`LinkedHashSet` for deterministic deduplication**: Preserves first-seen order when collecting `failingTests` and `referencedFiles` while avoiding duplicates—keeping LLM prompts and unit tests deterministic.
- **Pre-compiled `static final Pattern`s**: Regex patterns are compiled once at class load time and are thread-safe to share across Java 21 virtual threads (`Pattern` is immutable; only `Matcher` is per-call).

### Likely Interview Questions
1. **Why distill Maven logs before sending them to the LLM instead of passing the full output?**
   - *Answer:* Raw Maven + Spring stack traces are 75–95% noise (artifact downloads, reflection/JUnit/Spring proxy frames). Distilling cuts token cost and latency dramatically, keeps the prompt inside context limits, and improves fix accuracy by focusing attention on the root `Caused by:` chain and application frames.
2. **Why is `java.util.regex.Pattern` safe as a `static final` field across virtual threads, while `Matcher` is not?**
   - *Answer:* `Pattern` is immutable and compiled once; calling `pattern.matcher(line)` creates a lightweight, local `Matcher` holding per-match cursor state, so concurrent threads never share mutable state.
3. **Why did Error Prone warn on `String.split` and why is `String.lines()` better here?**
   - *Answer:* `String.split` silently drops trailing empty strings, treats its argument as a regex, and returns `[""]` on an empty string. `String.lines()` is purpose-built for line splitting, handles all line terminators cleanly, and allocates less.

### Official Docs
- [Java `String.lines()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#lines())
- [Error Prone — `StringSplitter`](https://errorprone.info/bugpattern/StringSplitter)
- [Java `Pattern` Thread Safety](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html)

---

## Slice 7: Bounded Agentic Triage & Patch Loop (`FR-3.1`, `FR-3.2`, `FR-3.3`)

### What was built
- **Hexagonal `LlmClient` Port & Sealed `LlmToolCall` (`com.autoheal.domain`)**:
  - `LlmClient.nextAction(List<LlmMessage>)` decouples the agentic loop from any specific LLM SDK or HTTP client.
  - `LlmToolCall` is a Java 21 `sealed interface` permitting exactly `ListFilesCall`, `ReadFileCall`, `SearchCodeCall`, and `ProposePatchCall`.
  - `LlmUsage`, `AttemptRecord`, `AttemptOutcome`, `HealingContext`, and `HealingResult` capture per-call tokens/cost/latency (`FR-3.3`) and per-attempt outcomes (`VERIFIED`, `POLICY_VIOLATION`, `APPLY_ERROR`, `COMPILE_ERROR`, `TEST_FAILURE`, `TIMEOUT`, `TOOL_LIMIT_EXCEEDED`).
- **Bounded Self-Correction Loop (`AgenticHealingService`)**:
  - Enforces max **3 patch attempts** and max **15 tool calls per attempt** (`FR-3.2`).
  - Copies the baseline workspace into an isolated per-attempt temp directory (cleaned up in `finally`) so failed attempts never contaminate subsequent attempts.
  - Runs the full verification pipeline per proposal: `TestIntegrityPolicy.validatePatch` → `PatchApplier.apply` → `SandboxRunner.runTests` → `SurefireReportParser.parse` → `TestIntegrityPolicy.verifyOutcome`, feeding distilled errors back into the LLM conversation on failure.

### Key Concepts Used
- **Java 21 `sealed interface` + Exhaustive Pattern-Matching `switch`**: Declaring `sealed interface LlmToolCall permits ...` tells the compiler the exact closed set of tool calls. A `switch (toolCall)` over those record types requires no `default` branch—if a new tool is added later, the compiler immediately flags every unhandled `switch`.
- **Hexagonal Testing (Ports & Adapters)**: By putting `LlmClient` and `SandboxRunner` in `com.autoheal.domain`, we tested all retry, policy, apply-error, compile-error, timeout, and tool-cap branches deterministically in <300ms using a scripted fake `LlmClient`—without touching network APIs or spending tokens.
- **`BigDecimal` for Financial/Cost Accounting**: Using `BigDecimal` (not `double`) for `estimatedCostUsd` prevents binary floating-point rounding drift when summing per-call LLM costs across attempts and daily budget checks.

### Likely Interview Questions
1. **Why use a Java 21 `sealed interface` for `LlmToolCall` instead of a generic `Map<String, Object>`?**
   - *Answer:* A sealed hierarchy gives compile-time type safety and exhaustive `switch` checks. Every tool's arguments are validated in its `record` compact constructor, and adding a new tool forces the compiler to catch any missed dispatch branch.
2. **Why copy the baseline workspace before every patch attempt?**
   - *Answer:* If attempt 1 partially modifies code or introduces a compilation error, applying attempt 2 on top of dirty files would cause search/replace mismatches or leftover broken code. Starting each attempt from a clean copy (and deleting it in `finally`) keeps attempts isolated and reproducible.
3. **Why use `BigDecimal` instead of `double` for LLM cost accounting?**
   - *Answer:* Binary floating-point (`double`) cannot represent base-10 fractions like `0.0001` accurately, leading to drift when summing thousands of API calls. `BigDecimal` preserves exact decimal precision matching PostgreSQL's `NUMERIC(10, 6)`.

### Official Docs
- [JEP 409 — Sealed Classes](https://openjdk.org/jeps/409)
- [JEP 441 — Pattern Matching for `switch`](https://openjdk.org/jeps/441)
- [Java `BigDecimal`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html)

---

## Slice 8: Gemini `LlmClient` HTTP Adapter (`FR-3.1`, `FR-3.3`)

### What was built
- Added `spring-boot-starter-web` to `backend/pom.xml` (for Spring `RestClient`, Jackson `ObjectMapper`, and Week 3 REST/SSE endpoints).
- Created `GeminiProperties` (`@ConfigurationProperties(prefix = "autoheal.llm.gemini")` Java `record`) bound to env-var placeholders in `application.yml` (`GEMINI_API_KEY`, `GEMINI_BASE_URL`, `GEMINI_MODEL`, per-million token prices).
- Implemented `GeminiLlmClient` (`com.autoheal.infra.llm`) implementing `com.autoheal.domain.LlmClient`:
  - Sends `POST /v1beta/models/{model}:generateContent` with `x-goog-api-key` header (never in the URL query string).
  - Declares all 4 structured tools (`list_files`, `read_file`, `search_code`, `propose_patch`) with `function_calling_config.mode = "ANY"`.
  - Maps `functionCall` responses into sealed `LlmToolCall` records and `usageMetadata` into `LlmUsage` with `BigDecimal` USD cost calculation and wall-clock latency.
  - Fails loudly with `LlmException` on missing API key, non-2xx HTTP status, invalid JSON, or missing function call.
- Wired `DockerSandboxConfig` (`@Bean SandboxRunner`) and `@Service` on `AgenticHealingService` so the entire agentic pipeline boots cleanly in Spring (`AutoHealApplicationTest`).
- Tested end-to-end HTTP serialization, header hygiene, tool parsing, cost math, and error paths using an in-process JDK `HttpServer` (`GeminiLlmClientTest`).

### Key Concepts Used
- **Spring `RestClient` vs `WebClient`**: `RestClient` (Spring 6.1+) provides a fluent, synchronous HTTP API that pairs naturally with Java 21 Virtual Threads—giving high I/O concurrency without reactive `Mono`/`Flux` boilerplate.
- **`@ConfigurationProperties` on Java `record`**: Immutable, type-safe configuration binding scanned via `@ConfigurationPropertiesScan`. Keeps secrets, URLs, model names, and prices out of source code.
- **API Key in HTTP Header (`x-goog-api-key`) vs Query String (`?key=...`)**: Passing secrets in headers keeps them out of URLs, reverse-proxy access logs, and exception messages.
- **In-Process `com.sun.net.httpserver.HttpServer` in Tests**: Lets us verify real HTTP requests, headers, JSON schemas, and error status codes (`429`) in <250ms with zero external mock-server dependencies.

### Likely Interview Questions
1. **Why send the Gemini API key in the `x-goog-api-key` header instead of `?key=...` in the URL?**
   - *Answer:* URLs are routinely logged by HTTP clients, reverse proxies, and exception stack traces (`RestClientException`). Keeping the API key in a header prevents accidental credential leakage in logs.
2. **Why use Spring `RestClient` instead of `WebClient` or `RestTemplate`?**
   - *Answer:* `RestTemplate` is in maintenance mode, and `WebClient` requires pulling in reactive WebFlux. With Java 21 Virtual Threads enabled, blocking `RestClient` calls scale cleanly while keeping code and stack traces simple.
3. **Why force `function_calling_config.mode = "ANY"` when calling Gemini?**
   - *Answer:* Our agent loop expects a strictly typed tool call (`list_files`, `read_file`, `search_code`, or `propose_patch`) on every turn. Setting `mode: ANY` prevents the model from replying with free-form prose that would break parsing and waste a turn.

### Official Docs
- [Spring Framework — `RestClient`](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-restclient)
- [Spring Boot — `@ConfigurationProperties`](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties)
- [Gemini API — Function Calling](https://ai.google.dev/gemini-api/docs/function-calling)

---

## Slice 9: GitHub Draft PR Client & PR Body Formatter (`FR-5.1`, `FR-5.2` — End of Week 2)

### What was built
- **Hexagonal `GitHubPrClient` Port (`com.autoheal.domain`)**:
  - `GitHubPrClient.createDraftPullRequest(PullRequestRequest)` returning `PullRequestResult(number, htmlUrl, branchName)` with typed `GitHubException`.
- **GitHub Git Data API Adapter (`GitHubRestPrClient` & `GitHubProperties` in `com.autoheal.infra.github`)**:
  - Authenticates via fine-grained PAT in `Authorization: Bearer` header from `${GITHUB_TOKEN:}` (`autoheal.github.*` in `application.yml`).
  - Executes the 6-step Git Data API flow over HTTPS (no `git push` shell-out): `GET /git/commits/{sha}` → `POST /git/blobs` → `POST /git/trees` → `POST /git/commits` → `POST /git/refs` → `POST /pulls` with `"draft": true`.
  - Verified end-to-end against an in-process JDK `HttpServer` (`GitHubRestPrClientTest`).
- **PR Body Formatter (`PullRequestBodyFormatter` in `com.autoheal.service`)**:
  - Generates the structured `FR-5.2` Markdown body: Root Cause Analysis, Summary of Changes, Verification Evidence (exit code, oracle `Class#method`, executed test count before → after, sandbox duration), and Agent Metrics (attempts, LLM calls, latency, tokens, USD cost).

### Key Concepts Used
- **GitHub Git Data API (Blobs → Tree → Commit → Ref)**: Git stores content as immutable objects (blobs for files, trees for directories, commits pointing to trees + parents, refs for branches). Using the Git Data API lets a stateless backend create multi-file commits and branches purely over REST without configuring SSH keys or shelling out to `git push`.
- **Draft Pull Requests (`draft: true`)**: Enforces AutoHeal's non-goal of never auto-merging AI patches without human review—Draft PRs clearly signal an automated candidate fix with verifiable test evidence.
- **Fine-Grained PAT + Header Auth**: Scoped only to the demo repository (`contents: write`, `pull_requests: write`) and loaded from env vars (`GITHUB_TOKEN`), never hardcoded or passed into sandbox containers.

### Likely Interview Questions
1. **Why use the GitHub Git Data API (`blobs` → `trees` → `commits` → `refs`) instead of `git push` or the Contents API?**
   - *Answer:* Shelling out to `git push` requires managing local Git credentials/SSH keys and creates command-injection surface. The Contents API only updates one file per commit, whereas the Git Data API creates a single atomic multi-file commit (source fix + new regression test) cleanly over HTTPS.
2. **Why open a Draft PR instead of a regular PR or auto-merging?**
   - *Answer:* Even with a green sandbox build and Test Integrity Policy, an AI patch can still overfit to the test suite or miss subtle domain invariants. Opening a Draft PR with full root-cause analysis and verification evidence keeps a human engineer in the approval loop.

### Official Docs
- [GitHub REST API — Git Database (Blobs, Trees, Commits, Refs)](https://docs.github.com/en/rest/git)
- [GitHub REST API — Pull Requests (`draft`)](https://docs.github.com/en/rest/pulls/pulls#create-a-pull-request)


---

## Slice 10: PostgreSQL 16 + `pgvector`, Flyway & JDBC Persistence (`PRD §5`, `ADR-005`)

### What was built
- **Local Postgres + `pgvector` Compose Stack (`docker-compose.yml`)**:
  - Runs `pgvector/pgvector:pg16` with healthcheck (`pg_isready`), persistent named volume, and env-var password/port overrides (`POSTGRES_PASSWORD`, `POSTGRES_PORT`).
- **Flyway Schema Migration (`backend/src/main/resources/db/migration/V1__initial_schema.sql`)**:
  - Installs `vector` extension and creates all 6 PRD §5 tables: `incident_runs`, `incident_attempts` (`UNIQUE(run_id, attempt_no)`), `llm_calls` (with `created_at` index for daily budget sums), `incident_events` (`JSONB` payload + `UNIQUE(run_id, event_seq)`), `incident_memory` (`vector(768)` + HNSW cosine index), and `benchmark_reports`.
- **Pure Domain Models & `IncidentRepository` Port (`com.autoheal.domain`)**:
  - Enums (`IncidentSource`, `IncidentStatus`, `IncidentStage`, `IncidentEventType`) and immutable records (`IncidentRun`, `IncidentAttempt`, `LlmCallRecord`, `IncidentEvent`) with zero Spring/JDBC imports (enforced by ArchUnit).
- **JDBC Persistence Adapter (`JdbcIncidentRepository` in `com.autoheal.infra.persistence`)**:
  - Implemented with Spring Boot 3.4's `JdbcClient` (`ADR-005`).
  - `appendEvent` uses `@Transactional` + `SELECT 1 FROM incident_runs WHERE id = :runId FOR UPDATE` before computing `COALESCE(MAX(event_seq), 0) + 1`, guaranteeing gap-free monotonic SSE `event_seq` under concurrent writers.
  - Uses `OffsetDateTime` in UTC for `TIMESTAMPTZ` columns and `BigDecimal` for `NUMERIC(10, 6)` USD cost aggregation.
- **Testcontainers Integration Tests (`PostgresTestContainer`, `JdbcIncidentRepositoryTest`, `AutoHealApplicationTest`)**:
  - Uses Spring Boot `@ServiceConnection` with `pgvector/pgvector:pg16` (and `docker-java.properties` `api.version=1.44` for Docker Engine 29 compatibility) to verify Flyway migrations, HNSW index, run lifecycle, unique attempt constraint, daily cost windowing, and SSE `Last-Event-ID` replay.

### Key Concepts Used
- **Spring `JdbcClient` vs JPA/Hibernate**: `JdbcClient` (Spring Framework 6.1+) is a fluent wrapper over JDBC that lets you write explicit SQL with named parameters and map rows directly to immutable Java 21 `record`s—ideal when using Postgres-specific features (`JSONB`, `pgvector`, `RETURNING`, `FOR UPDATE`).
- **Flyway Versioned Migrations (`V1__*.sql`)**: Tracks applied SQL scripts in `flyway_schema_history` with checksums so every environment (local, Testcontainers, production VM) gets the exact same schema automatically at startup.
- **Row-Level Locking (`SELECT ... FOR UPDATE`) + `@Transactional`**: Locking the parent `incident_runs` row inside a transaction serializes concurrent `appendEvent` calls for the same run without locking other runs, preventing `UNIQUE (run_id, event_seq)` race conditions.
- **Spring Boot `@ServiceConnection` + Testcontainers**: Automatically injects the ephemeral container's dynamic JDBC URL, username, and password into Spring Boot's `DataSource` and Flyway without manual `@DynamicPropertySource` boilerplate.

### Likely Interview Questions
1. **Why did you choose `JdbcClient` + Flyway instead of Spring Data JPA / Hibernate?**
   - *Answer:* Our schema relies on PostgreSQL-specific types (`JSONB`, `pgvector` `vector(768)`) and our hexagonal architecture keeps `com.autoheal.domain` free of framework annotations. `JdbcClient` maps explicit SQL straight into Java 21 `record`s with zero entity-mapping overhead or lazy-loading pitfalls.
2. **How do you guarantee SSE `event_seq` is strictly monotonic per run if two threads emit events at once?**
   - *Answer:* Inside `@Transactional`, `appendEvent` acquires a row-level lock on the parent `incident_runs` row (`SELECT 1 FROM incident_runs WHERE id = :runId FOR UPDATE`) before inserting `COALESCE(MAX(event_seq), 0) + 1`. That serializes writes per run while allowing different runs to execute in parallel, and `UNIQUE (run_id, event_seq)` enforces the invariant at the DB level.
3. **Why use an HNSW index instead of IVFFlat on `incident_memory.embedding`?**
   - *Answer:* IVFFlat requires training cluster centroids on representative data before building the index, so it performs poorly when created on an empty table at migration time. HNSW builds a navigable graph incrementally and works well even starting from zero rows.

### Official Docs
- [Spring Framework — `JdbcClient`](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html#jdbc-JdbcClient)
- [Spring Boot — Testcontainers `@ServiceConnection`](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Flyway — Versioned Migrations](https://documentation.red-gate.com/fd/migrations-184127470.html)
- [pgvector — HNSW Indexing](https://github.com/pgvector/pgvector#hnsw)


---

## Slice 11: Scenario Catalog & Persisted SSE Event Stream Service (`FR-1.1`, `PRD §4.2`)

### What was built
- **Domain Types (`com.autoheal.domain`)**:
  - `ScenarioDefinition` record (`id`, `title`, `description`, `language`, `branch`, `oracleTestClass`, `oracleTestMethod`), `RunMode` enum (`LIVE`, `REPLAY`), and typed exceptions (`ScenarioNotFoundException`, `QueueCapacityExceededException`).
- **Scenario Registry (`ScenarioCatalog` in `com.autoheal.service`)**:
  - Registers the 4 deterministic `demo-service` bug branches (`payment-npe-01`, `pagination-off-by-one-02`, `inventory-race-03`, `jpql-typo-04`) in stable order (`LinkedHashMap` wrapped with `Collections.unmodifiableMap`) with their exact JUnit oracle tests.
- **Persisted + Live SSE Event Stream (`IncidentEventStreamService` in `com.autoheal.service`)**:
  - `publish(...)` serializes structured event payloads to JSON, persists them via `IncidentRepository.appendEvent(...)` (receiving a monotonic `event_seq`), fans out to live subscribers, and cleans up subscriber lists on terminal `COMPLETED` / `FAILED` events.
  - `subscribe(runId, lastEventId, onEvent)` registers the subscriber *before* reading historical events (`findEventsAfter(runId, lastEventId)`), and `OrderedSubscriber` deduplicates by `eventSeq > lastDeliveredSeq` inside a synchronized guard.

### Key Concepts Used
- **Subscribe-Before-Replay Pattern (No-Gap SSE Resume)**: If you query the DB first and subscribe second, an event published in the gap between those two calls is lost forever. Subscribing first and deduplicating by monotonic `eventSeq` guarantees at-least-once persistence with exactly-once ordered delivery to each SSE client.
- **`ConcurrentHashMap` + `CopyOnWriteArrayList`**: Safe concurrent reads/writes across virtual threads with minimal lock contention—ideal when reads (event fan-out) far outnumber writes (client connect/disconnect).
- **Ordered Immutability (`LinkedHashMap` + `Collections.unmodifiableMap`)**: Unlike `Map.copyOf(...)` (whose iteration order is randomized per JVM run), wrapping a `LinkedHashMap` preserves deterministic scenario ordering for the Playground API.

### Likely Interview Questions
1. **How do you prevent losing or duplicating SSE events when a browser reconnects with `Last-Event-ID`?**
   - *Answer:* We persist every event to `incident_events` with a strictly monotonic `event_seq`. On reconnect, we register the live subscriber *before* querying `WHERE event_seq > :lastEventId`, and each subscriber tracks `lastDeliveredSeq` inside a synchronized method so any overlap between DB replay and live fan-out is silently skipped.
2. **What happens if one SSE client's socket drops while another client is watching the same run?**
   - *Answer:* Delivery wraps each subscriber callback at the boundary—if one throws a `RuntimeException` (disconnected client), we log a warning and remove only that subscriber from the `CopyOnWriteArrayList`, so all other subscribers continue uninterrupted.

### Official Docs
- [MDN — Using Server-Sent Events (`Last-Event-ID`)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)
- [Java `ConcurrentHashMap` & `CopyOnWriteArrayList`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html)


---

## Slice 12: Async Pipeline Orchestrator, Concurrency Semaphore & Replay Mode (`FR-1.2`, `PRD §2.1`, `§6.2`, `§6.3`)

### What was built
- **Domain Ports & Exceptions (`com.autoheal.domain`)**:
  - `LiveIncidentExecutor` functional interface decoupling queue/concurrency/replay orchestration from the concrete workspace+sandbox+agent execution.
  - Typed exceptions `ReplayNotAvailableException` and `IncidentRunNotFoundException` for clean HTTP error mapping.
  - Extended `IncidentRepository` & `JdbcIncidentRepository` with `findLatestSucceededRunByScenario(scenarioId)` (excluding `REPLAY` runs) verified in `JdbcIncidentRepositoryTest`.
- **Async Orchestrator (`IncidentOrchestratorService` & `PipelineProperties` in `com.autoheal.service`)**:
  - Dispatches live runs onto Java 21 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`), bounded by a fair `Semaphore(maxConcurrentRuns)` (default `3`, PRD §6.3).
  - Enforces a global queue cap (`AtomicInteger queuedCount` vs `maxQueuedRuns`, default `10`, PRD §6.2), failing fast with `QueueCapacityExceededException` when saturated.
  - Supports **Instant Replay Mode (`FR-1.2`)**: creates a fresh `REPLAY` run with `$0` cost, replays recorded `incident_events` from the latest real `SUCCEEDED` run, and completes the run record before emitting the terminal `COMPLETED` event.
  - Top-level async worker boundary catches unexpected failures, marks the run `FAILED`, emits a terminal `FAILED` event, and always releases the semaphore permit in `finally`.
- **Live Pipeline Executor (`DefaultLiveIncidentExecutor` & `WorkspaceConfig`)**:
  - Executes the full live flow: `JGit cloneBranch` (with `try-with-resources` cleanup) → baseline `SandboxRunner` (streaming stdout as `LOG_LINE` events) → `SurefireReportParser` → `LogDistiller` → `AgenticHealingService.heal` → persist attempts & LLM calls → `completeRun` + terminal SSE event.

### Key Concepts Used
- **Java 21 Virtual Threads + Fair `Semaphore`**: Virtual threads make blocking I/O (Docker, JGit, Gemini HTTP, Postgres) cheap without reactive complexity, while `new Semaphore(3, true)` acts as a bulkhead so heavy Docker sandboxes never exhaust VM RAM/CPU.
- **`AtomicInteger` Queue Backpressure**: Checking `queuedCount.incrementAndGet() > maxQueuedRuns` before submitting work prevents unbounded memory growth under traffic spikes and gives clients an immediate `503` suggesting Replay Mode.
- **Deterministic Concurrency Testing (`CountDownLatch`, zero `Thread.sleep`)**: Injecting a fake `LiveIncidentExecutor` with `CountDownLatch` gates lets us prove max concurrency never exceeds the semaphore limit and permits are always released on exception—in 14 milliseconds with zero flakiness.

### Likely Interview Questions
1. **If Java 21 virtual threads are lightweight, why do you still need a `Semaphore(3)`?**
   - *Answer:* Virtual threads scale I/O concurrency, not physical Docker container RAM/CPU. Each sandbox container uses up to 768 MB RAM and 1 vCPU; without a semaphore bulkhead, 50 concurrent requests would spawn 50 containers and OOM the host VM.
2. **How do you avoid a race condition where a client receives the `COMPLETED` SSE event and immediately calls `GET /incidents/{runId}` only to see `QUEUED`?**
   - *Answer:* We always call `incidentRepository.completeRun(...)` *before* publishing the terminal `COMPLETED` / `FAILED` event to `IncidentEventStreamService`. That guarantees the database state is already committed before any subscriber is notified.

### Official Docs
- [JEP 444 — Virtual Threads](https://openjdk.org/jeps/444)
- [Java `Semaphore` & `CountDownLatch`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Semaphore.html)


---

## Slice 13: Bucket4j Rate Limiting, Daily Spend Breaker & REST/SSE API (`PRD §4.1`, `§4.2`, `§6.2`)

### What was built
- **Abuse & Cost Protection (`AbuseProtectionService` & `AbuseProtectionProperties` in `com.autoheal.service`)**:
  - Added `com.bucket4j:bucket4j-core:8.10.1` for in-memory per-IP token buckets (`3 live runs/hour` default, `ConcurrentHashMap<String, Bucket>`).
  - Enforces a global daily LLM spend circuit breaker by summing `llm_calls.cost_usd` for the current UTC day via `IncidentRepository.sumDailyLlmCostUsd` against `autoheal.abuse.daily-budget-usd` (`$2.00` default). Checks the global budget *before* consuming an IP token so users aren't penalized when the breaker is tripped.
  - `REPLAY` mode bypasses both guards so visitors can always watch a recorded run with zero LLM spend.
- **REST & SSE API (`com.autoheal.api`)**:
  - `ScenarioController`: `GET /api/v1/scenarios` → list of all 4 scenarios with live `replayAvailable` flags.
  - `IncidentController`:
    - `POST /api/v1/incidents/simulate` → `202 Accepted` with `runId`, `status: QUEUED`, and `streamUrl`.
    - `GET /api/v1/incidents/{runId}` → full `IncidentRunResponse` including `attempts`.
    - `GET /api/v1/incidents/{runId}/stream` (`text/event-stream`) → Spring `SseEmitter` honoring `Last-Event-ID`, sending structured `SseEventEnvelope` JSON, scheduling `: ping` heartbeats every 15s, and tearing down subscriptions cleanly on `COMPLETED`/`FAILED`/disconnect.
  - `GlobalExceptionHandler` (`@RestControllerAdvice`): maps `ScenarioNotFoundException`/`IncidentRunNotFoundException` → `404`, `ReplayNotAvailableException` → `409`, `RateLimitExceededException`/`DailyBudgetExceededException` → `429` (`suggestedMode: REPLAY`), `QueueCapacityExceededException` → `503` (`suggestedMode: REPLAY`), `IllegalArgumentException` → `400`.
  - `WebCorsConfig`: restricts `/api/**` CORS to `${FRONTEND_ORIGIN:http://localhost:3000}`.

### Key Concepts Used
- **Token Bucket Algorithm (Bucket4j)**: Unlike a fixed-window counter (which allows 2x bursts across window boundaries), a token bucket (`capacity = 3`, `refillGreedy(3, 1h)`) smoothly refills tokens over time while bounding burst traffic per client IP.
- **Trusted Proxy Hygiene (`trust-forwarded-for`)**: Only reading `X-Forwarded-For` when explicitly configured behind Nginx prevents clients from spoofing arbitrary IPs to bypass per-IP rate limiting when the backend is exposed directly.
- **Spring `SseEmitter` + Heartbeats**: Reverse proxies (Nginx, cloud load balancers) drop idle HTTP connections after ~60s. Sending an SSE comment (`: ping`) every 15s keeps the stream alive without triggering client `onmessage` handlers.
- **Global `@RestControllerAdvice`**: Centralizes exception-to-HTTP-status mapping so controllers stay thin and never contain `try/catch` boilerplate.

### Likely Interview Questions
1. **Why check the global daily budget circuit breaker *before* consuming a token from the user's Bucket4j IP bucket?**
   - *Answer:* If the global daily budget is already exhausted, the request will be rejected with `429` regardless. Checking budget first avoids unfairly burning the user's hourly IP quota during a global circuit-breaker state.
2. **Why shouldn't a backend unconditionally trust the `X-Forwarded-For` header for rate limiting?**
   - *Answer:* Any client can send a forged `X-Forwarded-For: 1.2.3.4` header on every request to bypass per-IP limits. We gate `X-Forwarded-For` parsing behind `autoheal.abuse.trust-forwarded-for` (enabled only when Nginx overwrites the header in production).
3. **Why send `: ping` comments on an SSE stream instead of regular JSON events?**
   - *Answer:* Lines starting with `:` are SSE protocol comments—proxies see active bytes and reset idle timeouts, while the browser's `EventSource` ignores them without polluting the UI event log or advancing `Last-Event-ID`.

### Official Docs
- [Bucket4j Documentation](https://bucket4j.com/)
- [Spring MVC — `SseEmitter`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html#mvc-ann-async-http-streaming)
- [Spring MVC — `@RestControllerAdvice`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html)


---

## Slice 14: Scheduled Sweeper, Actuator Health & Production VM Stack (`FR-4.3`, `PRD §2.2`, `§4.2`, `§8` — End of Week 3 MVP)

### What was built
- **Scheduled Sandbox & Workspace Sweeper (`SandboxSweeperService` in `com.autoheal.service`)**:
  - Exposed `default int sweepExpiredContainers(Duration maxAge)` on `SandboxRunner` (overridden by `DockerSandboxRunner`).
  - Enabled `@EnableScheduling` on `AutoHealApplication`; `SandboxSweeperService.sweep()` runs every 60s (`autoheal.sweeper.interval-ms`) to force-remove `autoheal=true` containers older than 90s and delete orphan workspaces under `autoheal.workspace.root` older than 1 hour, isolating Docker errors so workspace cleanup still runs.
- **Health Endpoint (`spring-boot-starter-actuator`)**:
  - Exposes only `/actuator/health` (`management.endpoints.web.exposure.include: health`) for container healthchecks and uptime monitoring (`IncidentApiTest`).
- **Single-VM Production Deployment Stack**:
  - `backend/Dockerfile`: multi-stage Maven → Temurin 21 JRE image running as **UID `1000:1000`** with `/var/autoheal/runs` owned by UID `1000` (matching the non-root sandbox container UID).
  - `infra/nginx/autoheal.conf`: overwrites `X-Forwarded-For` with `$remote_addr`, and disables buffering (`proxy_buffering off`, `proxy_cache off`, `X-Accel-Buffering no`, `proxy_read_timeout 3600s`) on `/api/v1/incidents/*/stream`.
  - `docker-compose.prod.yml` & `.env.example`: wires `postgres` (`pgvector/pgvector:pg16`), `backend` (with `/var/run/docker.sock` and matching `/var/autoheal/runs:/var/autoheal/runs` bind mount), and `nginx`.

### Key Concepts Used
- **Sibling Container Bind-Mount Path Parity**: When a containerized backend spawns sibling sandbox containers via `/var/run/docker.sock`, the Docker daemon resolves bind-mount source paths on the *host* filesystem, not inside the backend container. Mounting `/var/autoheal/runs:/var/autoheal/runs` at the identical path on both host and backend guarantees every `/var/autoheal/runs/<runId>` bind mount resolves cleanly.
- **Nginx SSE Reverse Proxy (`proxy_buffering off`)**: By default, Nginx buffers upstream responses in 4–8 KB chunks before flushing to the browser, which stalls small SSE events until the buffer fills or times out. Turning off buffering and setting `Connection ""` over HTTP/1.1 delivers SSE events immediately.
- **Defense-in-Depth Cleanup (`try/finally` + Scheduled Sweeper)**: `try/finally` handles normal completion and exceptions, while a periodic sweeper recovers from hard JVM kills (`SIGKILL`, OOM, VM reboot) where `finally` blocks never execute.

### Likely Interview Questions
1. **Why must `/var/autoheal/runs` be mounted at the exact same path inside the backend container as on the host VM?**
   - *Answer:* Because the backend talks to the host's Docker daemon over `/var/run/docker.sock` (Docker-out-of-Docker, not Docker-in-Docker). When the backend tells Docker to bind-mount `workspaceDir` into a sandbox container, the Docker daemon looks up that path on the *host*. Identical paths prevent "directory not found" or empty-mount bugs.
2. **Why do you need a scheduled sweeper if `DockerSandboxRunner` and `WorkspaceHandle` already clean up in `try/finally`?**
   - *Answer:* If the backend process crashes (`SIGKILL` / OOM) or the VM restarts mid-run, `finally` blocks never run. The sweeper finds any `autoheal=true` container older than 90s and any workspace directory older than 1 hour and removes them automatically.
3. **Why does Nginx need `proxy_buffering off` and `proxy_read_timeout 3600s` on the SSE endpoint?**
   - *Answer:* Without `proxy_buffering off`, Nginx holds small SSE frames in memory instead of streaming them in real time. Without a long `proxy_read_timeout`, Nginx closes the SSE connection after 60 seconds of quiet time.

### Official Docs
- [Spring Boot — Actuator Health Endpoint](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- [Spring Framework — `@Scheduled`](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [Nginx — `proxy_buffering`](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering)


---

## Slice 15: Next.js Live Incident Playground & Benchmarks UI (`FR-1.1`–`FR-1.5`)

### What was built
- **Next.js App Router Frontend (`frontend/`)** with strict TypeScript (`strict: true`, `noUncheckedIndexedAccess: true`), Tailwind CSS v4, ESLint (`--max-warnings 0`, `no-explicit-any: error`), Monaco Diff Editor (`@monaco-editor/react`), and Playwright E2E smoke tests.
- **Typed API & Route Registry (`src/lib/routes.ts`, `src/lib/types.ts`, `src/lib/api.ts`)**: Centralized API route builders using `NEXT_PUBLIC_API_BASE_URL` (browser connects directly to Spring Boot for SSE, bypassing Vercel function timeout limits per PRD §2.2), and typed `ApiClientError` surfacing `suggestedMode: "REPLAY"` on HTTP `429`/`503`.
- **Named SSE Stream Hook (`src/hooks/useIncidentStream.ts`)**: Subscribes to `GET /api/v1/incidents/{runId}/stream` via `EventSource`, registers explicit listeners for all 8 named `IncidentEventType` events (`STAGE_UPDATE`, `LOG_LINE`, `TOOL_CALL`, `PATCH_PROPOSED`, `PATCH_REJECTED`, `ATTEMPT_RESULT`, `COMPLETED`, `FAILED`), deduplicates by `eventId` across `Last-Event-ID` reconnects, and triggers `GET /api/v1/incidents/{runId}` on terminal events.
- **Unified Diff Parser & Monaco Diff Viewer (`src/lib/diffParser.ts`, `src/components/PatchDiffViewer.tsx`)**: Parses multi-file unified diffs into per-file original/modified buffers and dynamically imports Monaco `DiffEditor` with `ssr: false` so Next.js SSR stays clean.
- **Playground Workbench & Benchmarks Tab (`src/components/*`, `src/app/page.tsx`, `src/app/benchmarks/page.tsx`)**: Scenario selector with Live/Replay toggle, 5-stage numbered pipeline visualizer, `#0F172A` live terminal highlighting `BUILD FAILURE` → `BUILD SUCCESS`, verification/PR summary bar, and `/benchmarks` evaluation report view.

### Key Concepts Used
- **Named SSE Events (`event:` field) vs. Default `onmessage`**: When Spring's `SseEmitter.event().name(type)` sets a custom SSE event name, the browser's `EventSource.onmessage` callback does **not** fire. Clients must register `eventSource.addEventListener(type, handler)` for each named event type.
- **Direct Browser-to-Backend SSE (Bypassing Serverless Proxies)**: Serverless hosts like Vercel impose short HTTP function timeouts (10–60s) and buffer responses, which breaks long-lived SSE streams. Pointing `EventSource` directly at the Spring Boot / Nginx origin (with CORS restricted to the frontend origin) keeps streams reliable.
- **Client-Only Dynamic Import (`next/dynamic` with `ssr: false`)**: Monaco Editor accesses `window` and browser DOM APIs at module evaluation time. Loading it with `dynamic(..., { ssr: false })` prevents Next.js server-side rendering crashes while keeping the initial JS bundle small.

### Likely Interview Questions
1. **Why did `EventSource.onmessage` not receive your Spring Boot SSE events?**
   - *Answer:* Because `IncidentController` sets `.name(event.eventType().name())`, which emits `event: LOG_LINE` (etc.) in the SSE frame. The HTML5 EventSource spec only routes unnamed events (or `event: message`) to `onmessage`; named events require `addEventListener(eventName, ...)`.
2. **How do you prevent duplicate log lines when an SSE connection drops and reconnects?**
   - *Answer:* Each SSE frame carries an `id: <eventSeq>` header. The browser automatically sends `Last-Event-ID` on reconnect so the backend only replays newer events, and the frontend also keeps a `Set<number>` of seen `eventId`s as a client-side idempotency guard.
3. **Why convert unified diffs into separate `original` and `modified` buffers on the frontend instead of just rendering raw `+`/`-` text?**
   - *Answer:* Monaco's `DiffEditor` requires two text models (`original` and `modified`) to provide side-by-side alignment, inline word-level diff highlights, and Java syntax highlighting. Parsing the server's canonical unified diff per file gives reviewers an IDE-grade diff view while keeping raw unified diff available as a toggle.

### Official Docs
- [MDN — Using Server-Sent Events (`EventSource`)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)
- [Next.js — Lazy Loading & `next/dynamic`](https://nextjs.org/docs/app/guides/lazy-loading)
- [Playwright — Network Mocking (`page.route`)](https://playwright.dev/docs/mock)


---

## Slice 16: GitHub `workflow_run` Webhook, Constant-Time HMAC & Actions Log Zip (`PRD §4.1`)

### What was built
- **Domain Models & Port (`com.autoheal.domain`)**:
  - `GitHubActionsLogClient` port for downloading workflow run logs; `WebhookFailureEvent`, `WebhookProcessingResult`, and `WebhookSignatureException`.
- **Constant-Time HMAC-SHA256 Verifier (`WebhookSignatureVerifier` in `com.autoheal.service`)**:
  - Computes `HmacSHA256` over the raw request `byte[]` body and compares digests with `MessageDigest.isEqual(...)` (constant-time byte comparison).
  - Fails closed when `GITHUB_WEBHOOK_SECRET` is blank; rejects missing header, wrong prefix, invalid hex, wrong digest length, or mismatched MAC.
- **Bounded Actions Log Zip Adapter (`GitHubRestActionsLogClient` in `com.autoheal.infra.github`)**:
  - Downloads `GET /repos/{owner}/{repo}/actions/runs/{workflowRunId}/logs` via `RestClient` and streams `ZipInputStream` with a hard 512 KB extracted-bytes cap to defend against zip bombs.
- **Webhook Filtering, De-duplication & Pipeline Hook (`GitHubWebhookService`, `IncidentOrchestratorService.triggerWebhookRun`, `DefaultLiveIncidentExecutor.executeWebhookRun`)**:
  - Ignores non-`workflow_run` events and runs where `action != "completed"` or `conclusion != "failure"` (`200 OK`).
  - Deduplicates `X-GitHub-Delivery` using a synchronized bounded LRU `LinkedHashMap` (`200 OK` on duplicates).
  - Queues real failures through the same fair `Semaphore(3)` bulkhead (`IncidentSource.WEBHOOK`), downloads Actions logs, and feeds them into `LogDistiller` → `AgenticHealingService`.
- **Controller & Error Mapping (`GitHubWebhookController`, `WebhookResponse`, `GlobalExceptionHandler`)**:
  - `POST /api/v1/webhooks/github` accepts raw `byte[]` body, returns `202 Accepted` when queued, `200 OK` when ignored/duplicate, and `401 Unauthorized` (`INVALID_WEBHOOK_SIGNATURE`) on bad/missing signatures.

### Key Concepts Used
- **Constant-Time Comparison (`MessageDigest.isEqual`)**: Standard `Arrays.equals` or `String.equals` short-circuits on the first mismatched byte, leaking timing information that lets an attacker forge HMAC signatures byte-by-byte over repeated requests. `MessageDigest.isEqual` always compares every byte in fixed time.
- **Verifying HMAC on Raw `byte[]` (Not Parsed JSON)**: GitHub signs the exact HTTP request body bytes. If a controller deserializes to a DTO or `JsonNode` and re-serializes it, whitespace or key ordering changes and valid signatures fail.
- **Bounded Zip Extraction (Zip-Bomb Defense)**: A tiny compressed ZIP payload can expand to gigabytes in memory. Tracking `totalWritten` and capping at `MAX_EXTRACTED_BYTES` (512 KB) bounds heap usage before passing text to `LogDistiller`.

### Likely Interview Questions
1. **Why must you verify `X-Hub-Signature-256` against the raw `byte[]` body using `MessageDigest.isEqual`?**
   - *Answer:* Two reasons: (1) JSON parsing/re-serialization alters whitespace/key order and breaks the signature, and (2) `String.equals` exits early on the first differing byte, creating a timing side-channel. `MessageDigest.isEqual` compares all 32 bytes in constant time.
2. **Why return `200 OK` for ignored events and duplicate deliveries instead of `400` or `409`?**
   - *Answer:* GitHub treats non-2xx webhook responses as delivery failures and retries them with backoff. Returning `200 OK` for intentionally ignored events (e.g., `conclusion=success`) and already-processed `X-GitHub-Delivery` IDs prevents retry storms while keeping the endpoint idempotent.
3. **Why does the webhook flow still run a baseline `mvn test` inside the sandbox if it already downloaded the Actions log zip?**
   - *Answer:* The Actions log gives rich failure context for `LogDistiller`, while the baseline sandbox run generates fresh `target/surefire-reports/*.xml` files locally so `SurefireReportParser` has the exact oracle test list and baseline test count required by the Test Integrity Policy (`FR-3.4`).

### Official Docs
- [GitHub Docs — Validating Webhook Deliveries](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries)
- [GitHub REST API — Download Workflow Run Logs](https://docs.github.com/en/rest/actions/workflow-runs#download-workflow-run-logs)
- [Java `MessageDigest.isEqual`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html#isEqual(byte%5B%5D,byte%5B%5D))


---

## Slice 17: `pgvector` Historical RAG, Seed Corpus & Eval Leakage Guard (`FR-2.3` — End of Week 4)

### What was built
- **Domain Ports & Models (`com.autoheal.domain`)**:
  - `EmbeddingClient` (`EMBEDDING_DIMENSION = 768`), `IncidentMemorySource` (`SEED`, `VERIFIED_RUN`), `IncidentMemoryEntry`, and `IncidentMemoryRepository`.
  - Extended `HealingContext` with `List<IncidentMemoryEntry> historicalMatches` (keeping the 4-arg convenience constructor).
- **PostgreSQL `pgvector` Adapter (`JdbcIncidentMemoryRepository` in `com.autoheal.infra.persistence`)**:
  - Persists 768-dim embeddings via `CAST(:embedding AS vector)` and retrieves top-K nearest neighbors using cosine distance (`embedding <=> CAST(:queryEmbedding AS vector)`), returning `1 - distance` as cosine similarity.
  - Enforces the **eval leakage guard** in SQL: `(:excludeOriginBugId::text IS NULL OR origin_bug_id IS NULL OR origin_bug_id <> :excludeOriginBugId::text)`.
  - Verified against a real `pgvector/pgvector:pg16` Testcontainers instance (`JdbcIncidentMemoryRepositoryTest`).
- **Gemini Embedding Adapter (`GeminiEmbeddingClient` in `com.autoheal.infra.llm`)**:
  - Calls `POST /v1beta/models/text-embedding-004:embedContent` with `outputDimensionality: 768`, validates vector length, and throws `LlmException` on missing key or dimension mismatch.
- **Seed Corpus & Self-Populating Memory (`IncidentMemorySeedCatalog`, `IncidentMemoryService`)**:
  - 30 curated Java/Spring Boot incident write-ups (distinct from the 4 Playground scenarios).
  - `seedIfEmpty()` idempotently embeds and inserts the seed corpus; `retrieveSimilar(sig, excludeOriginBugId, ragEnabled)` returns top-2 matches (and skips cleanly when `ragEnabled=false` for ablation or when embeddings are unavailable); `recordVerifiedResolution(...)` stores every verified fix back into `incident_memory`.
- **Pipeline Wiring (`AgenticHealingService`, `DefaultLiveIncidentExecutor`)**:
  - Injects top-2 historical matches into the agent's initial user prompt (`Similar past incidents (for reference)`) and records verified fixes after green builds.

### Key Concepts Used
- **`pgvector` Cosine Distance (`<=>`) & HNSW Index**: The `<=>` operator computes cosine distance (`0` = identical direction, `2` = opposite), so `1 - (a <=> b)` yields cosine similarity in `[-1, 1]`. Using an HNSW index (`vector_cosine_ops`) gives fast approximate nearest-neighbor search even on empty or small tables (unlike `ivfflat`, which requires training data).
- **Evaluation Leakage Guard (`origin_bug_id`)**: In a self-populating RAG system, running an evaluation suite multiple times would let later runs retrieve the exact answer from an earlier run of the *same* bug, artificially inflating fix rates. Filtering out rows matching `origin_bug_id` guarantees honest benchmark numbers.
- **Graceful Degradation for Optional Context**: RAG improves fix quality but should never block a run if the embedding endpoint is temporarily unavailable or unconfigured; `IncidentMemoryService` logs a warning and returns `List.of()` so core healing still proceeds.

### Likely Interview Questions
1. **Why did you use an HNSW index instead of IVFFlat on `incident_memory.embedding`?**
   - *Answer:* IVFFlat requires a representative dataset to exist before building clusters and degrades if created on an empty table. HNSW builds a multi-layer navigable graph incrementally, so it works out of the box from migration day 1 with strong recall.
2. **What is data leakage in a self-populating RAG benchmark, and how did you prevent it?**
   - *Answer:* Because AutoHeal writes every verified fix back to `incident_memory`, running a 3× evaluation pass would allow pass 2 to retrieve pass 1's exact fix for the same bug. We tag every `VERIFIED_RUN` row with `origin_bug_id` and exclude `origin_bug_id = :excludeOriginBugId` in the SQL `WHERE` clause during retrieval.
3. **Why keep `ragEnabled` as an explicit parameter on `retrieveSimilar(...)`?**
   - *Answer:* PRD §7 requires an ablation study (RAG on vs. RAG off) on the exact same pipeline and bug corpus. A per-call boolean lets the evaluation harness toggle RAG cleanly without swapping beans or restarting the app.

### Official Docs
- [`pgvector` — Indexing (HNSW vs IVFFlat) & Distance Operators](https://github.com/pgvector/pgvector#hnsw)
- [Gemini API — Embeddings (`embedContent`)](https://ai.google.dev/gemini-api/docs/embeddings)


---

## Slice 18: Wiring GitHub Draft PR Creation into `DefaultLiveIncidentExecutor` (`FR-5.1`, `FR-5.2`)

### What was built
- **In-Memory Patched File Propagation (`AppliedPatch` & `HealingResult`):**
  - Extended `AppliedPatch` with `Map<String, String> fileContents` (populated directly by `PatchApplier` during Phase 2 before disk cleanup) and propagated `modifiedFiles`, `createdFiles`, and `patchedFileContents` through `AgenticHealingService` into `HealingResult`.
  - This guarantees the GitHub Git Data API receives the exact verified file contents even after `AgenticHealingService` deletes each attempt's temporary workspace in its `finally` block.
- **Dedicated `DraftPullRequestService` (`com.autoheal.service`):**
  - Extracted PR orchestration into a focused 3-dependency `@Service` (`GitHubPrClient`, `PullRequestBodyFormatter`, `PipelineProperties`) instead of bloating `DefaultLiveIncidentExecutor`.
  - Respects the `autoheal.pipeline.pr-creation-enabled` flag, resolves `owner/repo` from either `repoName` or the GitHub clone URI (`https://github.com/owner/repo.git` / `git@github.com:owner/repo.git`), builds sanitized `autoheal/fix-{scenario}-{shortUuid}` branch names, formats the markdown PR body, and returns `Optional<PullRequestResult>`.
- **Pipeline & SSE Wiring (`DefaultLiveIncidentExecutor`):**
  - On a verified green build, transitions `incident_runs` to `IncidentStatus.OPENING_PR`, calls `DraftPullRequestService.openDraftPr(...)`, persists `prUrl` in `incident_runs`, and includes `prUrl` in the `COMPLETED` SSE event payload when present.

### Key Concepts Used
- **Passing Immutable Snapshots Across Ephemeral Lifecycles:** Because `attemptWorkspace` is deleted inside `AgenticHealingService`'s `finally` block for hygiene (`FR-4.3`), returning an immutable `Map.copyOf(...)` snapshot of modified/created files avoids re-applying patches or holding open temp directories during external GitHub API calls.
- **Single-Responsibility Service Extraction:** Keeping `DraftPullRequestService` separate from `DefaultLiveIncidentExecutor` prevents god-class creep while keeping every branch-naming, repo-parsing, and feature-flag edge case unit-testable in isolation (`DraftPullRequestServiceTest`).
- **`Optional<T>` for Feature-Flagged Side Effects:** Returning `Optional<PullRequestResult>` makes the "PR creation disabled / skipped" case explicit in the type system without `null` checks leaking across callers.

### Likely Interview Questions
1. **Why capture patched file contents in memory instead of keeping the attempt workspace on disk until the GitHub PR is created?**
   - *Answer:* Keeping the attempt directory alive couples local disk cleanup to external network I/O (GitHub API timeouts/retries). Capturing the handful of patched UTF-8 source files in an immutable map lets the `finally` block immediately delete the temp workspace while still giving the GitHub Git Data API exact blob contents.
2. **Why create a separate `DraftPullRequestService` instead of injecting `GitHubPrClient` directly into `DefaultLiveIncidentExecutor`?**
   - *Answer:* `DefaultLiveIncidentExecutor` already orchestrates workspace cloning, sandboxing, log distillation, healing, memory, and persistence. Isolating branch naming, `owner/repo` parsing, PR body formatting, and feature-flag gating into `DraftPullRequestService` keeps both classes cohesive and easy to unit-test.

### Official Docs
- [GitHub REST API — Git Data (Blobs, Trees, Commits, Refs)](https://docs.github.com/en/rest/git)
- [Java 21 `Map.copyOf` & Unmodifiable Collections](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Map.html#copyOf(java.util.Map))


---

## Slice 19: Benchmarks Summary API & PostgreSQL JSONB Persistence (`FR-1.5`, `PRD §4.1`, `§5`)

### What was built
- **Domain Models & Port (`com.autoheal.domain`):**
  - Immutable records `BenchmarkReport`, `BenchmarkSummary`, `BenchmarkAblationRow`, and `BenchmarkFailureBucket` matching the frontend's TypeScript `BenchmarkSummary` contract, plus `BenchmarkReportRepository` and `BenchmarkReportNotFoundException`.
- **PostgreSQL `JSONB` Adapter (`JdbcBenchmarkReportRepository` in `com.autoheal.infra.persistence`):**
  - Persists reports into `benchmark_reports` using `JdbcClient` with `CAST(:config AS jsonb)`, `CAST(:results AS jsonb)`, and `INSERT ... RETURNING` to return the generated `BIGSERIAL` ID in a single round-trip.
  - Queries the newest report via `ORDER BY created_at DESC, id DESC LIMIT 1` (verified against Testcontainers `pgvector:pg16` in `JdbcBenchmarkReportRepositoryTest`).
- **Service & REST Controller (`BenchmarkReportService` & `BenchmarkController`):**
  - Exposes `GET /api/v1/benchmarks/summary`, deserializing the latest report's `results` JSONB and populating `reportId` and `generatedAt` from the DB row when omitted in JSON.
  - Wired `BenchmarkReportNotFoundException` into `GlobalExceptionHandler` (`404 NOT_FOUND`) so the Next.js Benchmarks tab cleanly renders its "No benchmark report published yet" empty state when the table is empty (`BenchmarkApiTest`).

### Key Concepts Used
- **PostgreSQL `INSERT ... RETURNING` with `JSONB`:** Returning the generated primary key and normalized `JSONB` text in the same SQL statement avoids a second `SELECT` round-trip and guarantees the caller immediately has the persisted row's ID.
- **Defensive Compact Constructors for Record Deserialization:** Defaulting nullable `List` / `BigDecimal` fields inside `BenchmarkSummary`'s compact constructor keeps `com.autoheal.domain` 100% free of Jackson annotations while still handling partial JSON safely.
- **REST 404 as Explicit Empty State:** Returning `404` for "no latest benchmark report yet" lets the frontend distinguish between "no data yet" (`404` → empty state) and "backend unreachable / server error" (`5xx` → error banner).

### Likely Interview Questions
1. **Why store `benchmark_reports.results` as PostgreSQL `JSONB` instead of normalizing every ablation row and failure bucket into separate relational tables?**
   - *Answer:* Each benchmark report is an immutable evaluation snapshot read and written as a whole document. `JSONB` gives us schema flexibility as new evaluation metrics are added, validates JSON syntax at write time, and still supports indexing or SQL querying (`results->>'passAt1Rate'`) without join overhead.
2. **Why order by `created_at DESC, id DESC` instead of just `created_at DESC`?**
   - *Answer:* Two reports inserted within the same timestamp tick (common in fast automated tests or batch runs) would have a non-deterministic tie-breaker with `created_at DESC` alone. Adding `id DESC` (`BIGSERIAL`) guarantees deterministic ordering.

### Official Docs
- [PostgreSQL — `JSONB` Data Type](https://www.postgresql.org/docs/16/datatype-json.html)
- [Spring Framework — `JdbcClient`](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html#jdbc-JdbcClient)


---

## Slice 20: Daily Demo Repo Hygiene Scheduled Job (`FR-5.3`)

### What was built
- **Domain Port & Models (`com.autoheal.domain`):**
  - Added immutable records `OpenPullRequest(number, headBranch, createdAt)` and `GitBranchInfo(name, committedAt)`, plus the `GitHubRepoHygieneClient` port interface.
- **GitHub REST Hygiene Adapter (`GitHubRestRepoHygieneClient` in `com.autoheal.infra.github`):**
  - Implements `listOpenPullRequests` (`GET /pulls?state=open&per_page=100`), `closePullRequest` (`PATCH /pulls/{number}`), `listBranchesWithPrefix` (`GET /git/matching-refs/heads/{prefix}` + commit date lookup), and `deleteBranch` (`DELETE /git/refs/heads/{branch}`).
  - Enforces a defense-in-depth prefix guard inside `deleteBranch`: any branch not starting with `autoheal/` is rejected with `IllegalArgumentException` before any HTTP call is made (`GitHubRestRepoHygieneClientTest`).
- **Scheduled Cleanup Service (`DemoRepoHygieneService` in `com.autoheal.service`):**
  - Runs daily via `@Scheduled(cron = "${autoheal.hygiene.cron:0 0 3 * * *}")` when `autoheal.pipeline.pr-creation-enabled=true`.
  - Uses an injected `java.time.Clock` to compute the 24-hour staleness cutoff, closes only stale `autoheal/*` PRs, deletes only stale `autoheal/*` branches, and catches `RuntimeException` at the scheduled boundary so transient GitHub outages never kill the Spring scheduler (`DemoRepoHygieneServiceTest`).

### Key Concepts Used
- **Defense-in-Depth Safety Guards:** Both `DemoRepoHygieneService` (policy layer) and `GitHubRestRepoHygieneClient.deleteBranch` (adapter layer) independently verify the `autoheal/` branch prefix so a misconfiguration or bug can never delete `main` or `scenario/*` branches.
- **Injectable `java.time.Clock`:** Passing `Clock.fixed(...)` in tests makes 24-hour expiration logic 100% deterministic without `Thread.sleep` or brittle wall-clock offsets.
- **Spring `@Scheduled(cron = ...)` with Externalized Config:** Using a property placeholder with a sensible default (`0 0 3 * * *`) lets operators adjust the schedule via env vars (`AUTOHEAL_HYGIENE_CRON`) without rebuilding the JAR.

### Likely Interview Questions
1. **How do you make sure a scheduled cleanup job can never accidentally delete `main` or a developer's feature branch?**
   - *Answer:* We enforce the `autoheal/` prefix invariant in two layers: the service only filters PRs/branches starting with `autoheal/`, and the GitHub adapter's `deleteBranch` method refuses any branch name that doesn't start with `autoheal/` with an `IllegalArgumentException` before making any HTTP call.
2. **Why wrap the body of a `@Scheduled` method in a boundary `try/catch (RuntimeException)`?**
   - *Answer:* By default Spring's task scheduler logs uncaught exceptions and keeps running, but catching at the top-level scheduled boundary gives us structured logging, clean observability (`HygieneSummary`), and explicit failure handling without leaking stack traces into unrelated logs.

### Official Docs
- [Spring Framework — Task Execution and Scheduling (`@Scheduled`)](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [GitHub REST API — Git References (`matching-refs`, `DELETE ref`)](https://docs.github.com/en/rest/git/refs)
