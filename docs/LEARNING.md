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
