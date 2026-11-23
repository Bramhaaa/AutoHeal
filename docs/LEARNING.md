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
