# StackTrial interview guide

Everything here describes the code as it exists in this repository. Class names are real; file paths are relative to `backend/src/main/java/com/stacktrial/`.

Contents: [A. 60-second pitch](#a-60-second-project-explanation) · [B. 2-minute version](#b-2-minute-project-explanation) · [C. Architecture](#c-complete-architecture-explanation) · [D. Request flow](#d-end-to-end-request-flow) · [E. Files to study](#e-most-important-classes-and-files-to-study) · [F. Technologies](#f-important-technologies) · [G. Decisions](#g-important-engineering-decisions) · [H. 60 interview questions](#h-interview-questions) · [What I should learn first](#what-i-should-learn-first) · [How to explain the project](#how-to-explain-the-project)

---

## A. 60-second project explanation

"StackTrial answers one question before you merge: does this change break my Spring Boot project? You give it a Git repository and a proposed change: a Spring Boot upgrade, a Java version, a dependency version, or a code diff. It clones the repository, runs `mvn verify` on the unchanged code in a Docker container, applies the change to a separate copy, and runs the same build in an equivalent container. Then it parses the real results (exit code, compile errors, Surefire test reports, the dependency tree) and a deterministic comparison engine in plain Java decides the verdict: PASS, REGRESSION, IMPROVEMENT, BOTH_FAILED or INCONCLUSIVE. Only after that, it retrieves relevant migration documentation from PostgreSQL with pgvector and asks Gemini to explain the failure, citing evidence ids. The AI explains; it never decides. For example, upgrading a Spring Boot 2.7 service to 3.0 shows two tests going from 200 to 404, and the report points to the trailing-slash change in the Spring Boot 3.0 migration guide."

## B. 2-minute project explanation

"Upgrading Spring Boot or Java is risky because the failures show up in places you don't expect: a removed API, a changed default, a dependency that moved. Teams either upgrade blindly or spend days testing by hand.

StackTrial makes it an experiment with a control group. It's a Spring Boot 3.5 application on Java 21, built as a modular monolith with packages for workspace, execution, evidence, comparison and explanation.

When you submit a trial, the API stores it in PostgreSQL as QUEUED and returns 202 immediately; a bounded thread pool picks it up. The orchestrator clones the repository with the git CLI, resolves the ref to a commit, and makes two copies: baseline and trial. A strategy class applies the change to the trial copy only (for example it edits the Spring Boot parent version in pom.xml) and I record the real `git diff` so you can see exactly what changed.

Each copy then runs through the same method: docker-java creates a container from a Maven image, with memory, CPU and process limits, a non-root user, all capabilities dropped and a hard timeout. The workspace is copied in as a tar, so there are no host mounts, and the results are copied out afterwards. The container is always removed.

Parsers turn the build log, the Surefire XML and the dependency tree into structured evidence. The comparison engine is pure Java with an ordered decision table: timeout means INCONCLUSIVE, baseline passing and trial failing means REGRESSION, and so on. A test only counts as a regression if it passed in baseline and fails in trial, so pre-existing failures aren't blamed on the change.

Finally the explanation step retrieves documentation, with pgvector similarity search when Gemini is configured and PostgreSQL full-text search otherwise, and sends Gemini the final verdict, the evidence and those chunks. I validate every citation it returns and flag any id it invented. If Gemini isn't configured or fails, the report is still complete.

It's tested with JUnit and Testcontainers against real PostgreSQL, a test that inspects isolation inside a real container, and an end-to-end test that runs the real Spring Boot 2.7-to-3.0 regression through Docker."

## C. Complete architecture explanation

```text
Browser / curl
   │
web          TrialApiController (REST)  ·  TrialPageController (Thymeleaf)
   │
trial        TrialService ──▶ trialExecutor (bounded pool) ──▶ TrialOrchestrator
   │                                                        │  state in PostgreSQL via TrialStore
   ├── workspace    WorkspaceService, GitClient, PomInspector, ChangeService + 4 ChangeAppliers
   ├── execution    MavenRunExecutor ─▶ ContainerRunner (DockerContainerRunner, docker-java) ─▶ Docker Engine
   ├── evidence     EvidenceCollector: build log, Surefire XML, dependency tree parsers
   ├── comparison   ComparisonEngine (pure Java, VERDICT_RULES)
   └── explanation  KnowledgeRetriever (pgvector / full text), PromptBuilder, GeminiClient, CitationValidator
PostgreSQL 16 + pgvector (Flyway): trial, trial_run, test_result, evidence, finding, explanation, knowledge_chunk
```

Key points to say:
- **One deployable, clear modules.** Dependencies point one way: `web → trial → everything else`. `comparison` depends only on evidence records, so it can be unit-tested with no Spring at all.
- **External systems behind interfaces.** `ContainerRunner` (Docker), `ChangeApplier` (change types), `LlmClient` / `EmbeddingClient` (Gemini), `StorageService` (files). Tests replace only these seams.
- **Async with database state.** HTTP never waits for Docker. Every state change is a short transaction, so the UI can poll progress.
- **Fairness.** Container limits, user, network, timeout and script are global configuration; nothing per side except the workspace and the Java image derived from that side's pom.
- **Evidence first, AI second.** The verdict is stored before the AI step runs.

Details: [ARCHITECTURE.md](ARCHITECTURE.md), [PROJECT_FLOW.md](PROJECT_FLOW.md).

## D. End-to-end request flow

1. `POST /api/trials` → `TrialApiController.create` → `TrialService.create`: validate URL/ref/change, insert `trial` as `QUEUED`, `trialExecutor.execute(...)`, return `202`.
2. Worker thread: `TrialOrchestrator.execute(id)`.
3. **PREPARING**: `WorkspaceService.prepare` (git clone `--no-checkout`, checkout ref as detached HEAD, copy to `baseline/` and `trial/`), `PomInspector` (Java + Boot version), `ChangeService.apply` on `trial/`, `GitClient.diffAgainstHead` → stored diff, `RunnerImageResolver` per side.
4. **RUNNING**: `MavenRunExecutor.run(BASELINE)` then `run(TRIAL)` → `DockerContainerRunner.run` → `EvidenceCollector.collect` → `TrialStore.saveRun`.
5. **ANALYZING**: `ComparisonEngine.compare` → evidence codes, findings, verdict → `TrialStore.saveComparison`.
6. **EXPLAINING**: `ExplanationService.explain` → retrieval → (Gemini) → `CitationValidator` → `explanation` row.
7. **COMPLETED**; `finally` deletes workspaces and collected files.
8. `GET /api/trials/{id}` → `TrialReportService.report` → `TrialReport` JSON; `GET /trials/{id}` renders the same record.

## E. Most important classes and files to study

### P0: must know (9 files; together they explain the whole system)

| # | File | Why |
|---|---|---|
| 1 | `trial/TrialOrchestrator.java` | The whole pipeline in one method; states; error handling; cleanup |
| 2 | `trial/TrialService.java` | Validation, QUEUED, handing work to the thread pool, queue-full behaviour |
| 3 | `execution/DockerContainerRunner.java` | Every isolation control; copy in/out; timeout; cleanup |
| 4 | `execution/MavenRunExecutor.java` | The exact script both sides run; storage and redaction |
| 5 | `evidence/EvidenceCollector.java` | How raw files become `RunEvidence` |
| 6 | `comparison/ComparisonEngine.java` | Findings and the `VERDICT_RULES` decision table |
| 7 | `explanation/ExplanationService.java` | Why AI cannot change the verdict; fallback states |
| 8 | `explanation/KnowledgeRetriever.java` | Vector vs full-text retrieval |
| 9 | `web/api/TrialApiController.java` | The API surface: 202 + polling |

### P1: should know

`trial/TrialStore.java` (transactions), `trial/TrialExecutionConfiguration.java` (thread pool), `workspace/WorkspaceService.java` and `workspace/GitClient.java`, `workspace/change/ChangeApplier.java` + `SpringBootVersionChangeApplier.java`, `evidence/SurefireReportParser.java`, `evidence/MavenBuildLogParser.java`, `explanation/PromptBuilder.java`, `explanation/GeminiClient.java`, `explanation/CitationValidator.java`, `common/StackTrialProperties.java`, `db/migration/V1__init.sql`, test `comparison/ComparisonEngineTest.java`.

### P2: nice to know

`workspace/change/PomEditor.java`, `workspace/RepositoryUrlValidator.java`, `common/LogRedactor.java`, `evidence/DependencyTreeParser.java`, `explanation/KnowledgeIngestionService.java`, `explanation/KnowledgeRepository.java` (SQL), `trial/TrialRecovery.java`, `trial/TrialReportService.java`, `web/api/ApiExceptionHandler.java`, `execution/RunnerImageResolver.java`, tests `TrialLifecycleIT`, `DockerContainerRunnerIT`, `RealDockerE2ETest`.

## F. Important technologies

| Technology | Why this project uses it |
|---|---|
| **Java 21** | Current LTS. Records for all DTOs and evidence types (`RunEvidence`, `Finding`, `TrialReport`), switch expressions, pattern matching for `instanceof` in `GeminiClient`. |
| **Spring Boot 3.5** | The target users are Spring Boot developers; it gives typed config (`@ConfigurationProperties` → `StackTrialProperties`), JPA, validation, problem details, Thymeleaf, Actuator and Testcontainers integration, so effort goes into execution and comparison. |
| **Maven** | Builds StackTrial, and is the build tool StackTrial runs for target projects (`mvn verify`, `mvn dependency:tree`). V1 supports Maven targets only. |
| **PostgreSQL** | Trial state is relational and transactional (trial → runs → tests; evidence; findings). One datastore for state, full-text search and vectors. |
| **pgvector** | Stores 768-dimension embeddings in `knowledge_chunk.embedding` with an HNSW cosine index; `<=>` gives cosine distance in SQL. No separate vector database for 22 chunks. |
| **Flyway** | `V1__init.sql` creates the schema reproducibly; Hibernate runs with `ddl-auto: validate` so drift fails fast. |
| **JGit** | **Not used.** The project calls the `git` CLI through `ProcessBuilder` (`workspace/GitClient`). Reason: `git apply --check` / `git apply` semantics for diffs exactly match what developers run, there's no extra library, and git never executes repository code. Trade-off: git must be installed on the host and output is text. JGit would be the choice if the app had to run where no git binary exists. |
| **Docker** | Untrusted code runs in a disposable, resource-limited environment; baseline and trial get identical, clean JDK + Maven environments. |
| **docker-java** | Typed Docker Engine API (create with `HostConfig`, copy tar in/out, wait with timeout, kill, remove) instead of parsing CLI output. |
| **Testcontainers** | Integration tests run against real PostgreSQL + pgvector (`pgvector/pgvector:pg16`) via `@ServiceConnection`, so SQL like `<=>` and `to_tsquery` is really tested. |
| **Thymeleaf** | Server-rendered UI (dashboard, form, progress with auto-refresh, report). UI was priority 7; no JS build. |
| **Gemini** | Text generation (`generateContent`) and embeddings (`embedContent`, 768 dims) over simple REST; behind `LlmClient` / `EmbeddingClient` so it can be swapped or stubbed. |
| **RAG** | Grounds the explanation in curated documentation the reader can check, instead of the model's memory; citations make every claim traceable. |

## G. Important engineering decisions

| Decision | Why |
|---|---|
| **Modular monolith** | One developer, one deployable, one database. Microservices would add network calls and failure modes without solving any real problem here. Packages + interfaces still give clean seams. |
| **Async processing** | A trial takes seconds to minutes. `POST` returns 202 and a bounded `ThreadPoolTaskExecutor` runs it; the queue is bounded so overload fails fast (`Trial queue is full`). |
| **Database-backed state** | The `trial` row is the source of truth: status, current step, verdict. UI and API just read it. `TrialRecovery` marks interrupted trials `FAILED` on startup, so nothing stays `RUNNING` forever. |
| **Docker isolation** | Repository tests and Maven plugins can execute anything. Containers give limits, a non-root user, no capabilities, a timeout and disposal. |
| **Baseline vs trial** | A failing test means nothing without a control. A regression is "passed in baseline and fails in trial". |
| **Deterministic verdict** | The verdict must be reproducible and explainable. Pure Java, sorted iteration, sequential evidence codes, ordered rules, unit-tested per rule. |
| **AI only for explanation** | LLM output is not reproducible and can be wrong. It is useful to explain *why*, using the evidence. It runs after the verdict is stored and has no write path to it. |
| **PostgreSQL + pgvector** | One system for relational state, full-text fallback and vectors; same backups and transactions. |
| **No Kafka** | One machine, a handful of trials; a bounded executor + DB state is enough. Kafka adds a broker and the dual-write problem with no user-visible gain. Documented as a scale-up step. |
| **No Redis** | Nothing needs a cache or distributed lock: state is in PostgreSQL, the Maven cache is a Docker volume. |
| **No Kubernetes** | Single-user local tool. A Kubernetes Job runner could be added later behind `ContainerRunner`. |

---

## H. Interview questions

Each question: **Q** (question), **Testing** (what the interviewer is checking), **A** (a strong answer based on this code), **Follow-up** (what they may ask next).

### Java

**1. Q: Where do you use Java records, and why?**
Testing: modern Java, immutability.
A: All value types: `RunEvidence`, `TestCaseResult`, `Finding`, `EvidenceItem`, `ComparisonResult`, `TrialReport` and its nested views, `CreateTrialRequest`, and even `StackTrialProperties`. They're immutable and get `equals`/`hashCode` for free, which matters for the comparison engine: I test that the same inputs give *equal* outputs.
Follow-up: Why are JPA entities (`Trial`, `TrialRun`) not records? *(JPA needs a no-arg constructor and mutable state for dirty checking.)*

**2. Q: How do you guarantee the comparison output is deterministic in Java?**
Testing: awareness of ordering pitfalls (HashMap iteration).
A: Tests are keyed `class#name` in a `TreeMap`, dependency keys go through a `TreeSet`, evidence codes are assigned by `EvidenceLedger` in recording order, and the rule list is an immutable ordered `List`. No `HashMap` iteration, no clock, no randomness. `ComparisonEngineTest.sameInputsProduceIdenticalOutput` checks it.
Follow-up: What would break if you used `HashMap`? *(Evidence numbering could change between runs, so `EVIDENCE-004` would point at different facts.)*

**3. Q: How is the verdict table modelled?**
Testing: design, functional interfaces.
A: `VerdictRule` is a record of `name`, a `Predicate<ComparisonContext>`, a `Verdict`, and a `Function<ComparisonContext,String>` for the reason. `VERDICT_RULES` is a `List.of(...)`; `compare` streams it and takes `findFirst()`. Adding a rule is adding a row.
Follow-up: Why not a chain of if/else? *(The table is readable, testable per row, and order is explicit.)*

**4. Q: How do you handle errors without try/catch everywhere?**
Testing: exception design.
A: Expected problems have specific exceptions: `GitCommandException`, `UnsupportedProjectException`, `ChangeApplicationException`, `ContainerExecutionException`, `TrialFailureException`. The orchestrator catches them in one place and turns the message into `failure_reason`. Unexpected `RuntimeException`s are logged with a stack trace and also end as `FAILED`. Build failures aren't exceptions at all; they are evidence.
Follow-up: Checked or unchecked, and why? *(Unchecked; callers can't recover locally, the orchestrator handles them.)*

**5. Q: How do you run an external process safely from Java?**
Testing: ProcessBuilder, injection.
A: `GitClient` uses `ProcessBuilder` with an argument list (no shell), `--` before the URL, `GIT_TERMINAL_PROMPT=0`, `GIT_CONFIG_NOSYSTEM=1`, `-c protocol.ext.allow=never`, and a timeout that destroys the process. Refs are validated so they can't look like options.
Follow-up: What if git hangs on a credential prompt? *(Terminal prompt is disabled and the timeout kills it.)*

### Spring Boot

**6. Q: How is configuration structured?**
Testing: `@ConfigurationProperties`.
A: One record tree, `StackTrialProperties`, bound from `stacktrial.*` with `@DefaultValue`s: workspace, execution, runner (limits, images, timeout, network), gemini, rag. Environment variables override YAML (`STACKTRIAL_RUN_TIMEOUT`, `GEMINI_API_KEY`).
Follow-up: How do you test with different config? *(`@DynamicPropertySource`, or construct the record directly like `DockerContainerRunnerIT` does.)*

**7. Q: How does the Gemini bean appear only when a key exists?**
Testing: conditional beans.
A: `ExplanationConfiguration` has `@ConditionalOnExpression("!'${stacktrial.gemini.api-key:}'.isBlank()")`. Consumers inject `ObjectProvider<LlmClient>` and call `getIfAvailable()`; null means "store UNAVAILABLE".
Follow-up: Why not a no-op fake bean? *(A fake would look like a real answer; absence is honest.)*

**8. Q: How do you structure transactions in a long-running job?**
Testing: `@Transactional` scope.
A: Never one transaction around the whole trial (minutes, holding a connection). `TrialStore` methods are each `@Transactional`: `moveTo`, `saveRun`, `saveComparison`. Each commit is visible to the polling UI immediately.
Follow-up: What happens to partial data if the trial fails mid-way? *(It stays, which is useful for debugging; the trial is `FAILED` with a reason.)*

**9. Q: Why `spring.jpa.open-in-view: false`?**
Testing: OSIV awareness.
A: Controllers get `TrialReport` records built inside the service, not lazy entities. OSIV would hide lazy-loading in views and keep connections open during rendering.
Follow-up: How do you avoid N+1 when building the report? *(Explicit repository queries per table by trial id.)*

**10. Q: How do you handle errors in the REST API?**
Testing: `@RestControllerAdvice`, RFC 9457.
A: `ApiExceptionHandler` maps `NotFoundException` → 404, `IllegalArgumentException` and validation errors → 400, unreadable JSON / bad enum → 400, all as `ProblemDetail`.
Follow-up: Why does a clone failure not return an error to the POST? *(Clone happens asynchronously; it becomes `FAILED` with a reason.)*

### REST

**11. Q: Why does `POST /api/trials` return 202, not 201?**
Testing: HTTP semantics for async work.
A: The resource is created, but the work it represents hasn't happened. 202 Accepted with a `Location` header and `reportUrl` tells the client to poll `GET /api/trials/{id}` until `finished` is true.
Follow-up: Polling vs webhooks vs SSE? *(Polling is simplest for a local tool; SSE would be the next step for the UI.)*

**12. Q: Why do you return DTOs, not entities?**
Testing: API boundary design.
A: `TrialSummary`, `TrialReport` are records shaped for clients. Entities stay internal so schema changes don't break the API and lazy fields can't leak.
Follow-up: How would you version the API? *(`/api/v2` or a media type, only when a breaking change is needed.)*

**13. Q: How is input validated?**
Testing: validation layers.
A: Bean validation on `CreateTrialRequest` (`@NotBlank`, `@Size`, `@NotNull`), then semantic checks in `TrialService.validateChange` (fields per change type), and security checks in `RepositoryUrlValidator`. Versions are validated again by `PomEditor` before editing XML.
Follow-up: Why validate in the service and not only in the DTO? *(The UI form uses the same service, so rules live in one place.)*

### PostgreSQL

**14. Q: Describe the schema.**
Testing: data modelling.
A: `trial` (request, status, verdict), `trial_run` (one per side, unique on `trial_id, side`), `test_result` (per test case), `evidence` (codes unique per trial), `finding` (with JSONB arrays of evidence codes), `explanation` (one per trial), `knowledge_chunk` (with `vector(768)` and a generated `tsvector`). Children cascade on delete.
Follow-up: Why JSONB for evidence codes instead of a join table? *(They're always read together with the finding and never queried individually.)*

**15. Q: How does full-text search work here?**
Testing: PostgreSQL FTS.
A: `search_vector tsvector GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || content)) STORED` with a GIN index. The query is an OR of extracted terms passed to `to_tsquery`, ranked with `ts_rank`; weak hits under 35 % of the top rank are dropped in Java.
Follow-up: Why OR and not AND? *(A failure message rarely contains all terms of the right section.)*

**16. Q: Why Flyway with `ddl-auto: validate`?**
Testing: schema management.
A: Migrations are versioned, reviewed SQL; Hibernate only checks entities match, so drift fails at startup instead of corrupting data.
Follow-up: How do you change an applied migration? *(You don't; add `V2__…`.)*

### Docker

**17. Q: Which isolation controls do runner containers have?**
Testing: container security.
A: Memory with equal memory-swap, NanoCPUs, PIDs limit, user `1000:1000`, `CapDrop ALL`, `no-new-privileges`, not privileged, hard timeout with kill, force-remove in `finally`, no Docker socket, no host mount of the workspace. `DockerContainerRunnerIT` checks uid, `CapEff`, `NoNewPrivs`, memory and PIDs limits from inside a real container.
Follow-up: What's still not isolated? *(Network, and the shared Maven cache volume.)*

**18. Q: Why copy files in and out instead of bind-mounting?**
Testing: host safety.
A: A bind mount gives the container write access to a host path. Copying a tar in through the Engine API means the container can only change its own filesystem. Copy-out skips any path that would escape the target directory. It also works when the backend itself runs in a container.
Follow-up: Cost? *(Copy time, negligible for source trees.)*

**19. Q: How do you enforce a timeout?**
Testing: resource control.
A: `waitContainerCmd(...).awaitStatusCode(timeout)` throws when the time is up; I kill the container, still read its logs, and return exit `-1` with `timedOut=true`. The comparison turns any timeout into `INCONCLUSIVE`.
Follow-up: Why not REGRESSION? *(A half-finished run is not evidence.)*

**20. Q: How do baseline and trial get "equivalent" environments?**
Testing: experimental design.
A: Limits, user, network, timeout and the script are global config, and `ContainerRunSpec` has no fields for them. Only the workspace differs, plus the image when the Java version itself is the change (chosen from each side's pom).
Follow-up: What about the shared Maven cache? *(Same for both sides; it affects speed, not results.)*

**21. Q: Why run as uid 1000 but chown the cache volume with a root container?**
Testing: Docker volume ownership.
A: New named volumes are owned by root. A short-lived container runs `chown -R 1000:1000 /m2` as root with only the `CHOWN` capability and no network, once per app start. Runner containers then stay non-root.
Follow-up: Alternative? *(Pre-create the volume with the right owner, or use a per-trial cache.)*

### DevOps

**22. Q: How do you start the whole system locally?**
Testing: reproducibility.
A: `docker compose up -d` for PostgreSQL + pgvector, `mvn clean verify` to build and test, `java -jar target/stacktrial-0.1.0-SNAPSHOT.jar`, then `sh fixtures/create-demo-repos.sh` for demo repos. All in `RUNBOOK.md`.
Follow-up: Why not run the backend in compose too? *(It needs the Docker socket; running on the host keeps that explicit. Doable with a socket mount.)*

**23. Q: How is the app observable?**
Testing: logging, health.
A: Actuator `/actuator/health`, and every log line carries `[trial=<id>] [step=<STATE>]` from MDC set in the orchestrator, so one trial's lines can be grepped across threads.
Follow-up: What metrics would you add? *(Trial duration per phase, queue depth, verdict counts with Micrometer.)*

**24. Q: How do you handle secrets?**
Testing: secret hygiene.
A: `.env` is git-ignored, `.env.example` documents variables. The Gemini key goes only in a header. `LogRedactor` masks token formats and `password=`-style values before logs are stored or sent to Gemini.
Follow-up: What about secrets inside the target repo's tests? *(Redaction is best-effort pattern matching; documented as such.)*

### Git

**25. Q: How do you check out the baseline?**
Testing: git internals.
A: `git clone --no-checkout`, then try `rev-parse --verify <ref>^{commit}` and `origin/<ref>`, then `checkout --detach <sha>`. The resolved SHA is stored, so the report says exactly which commit was tested.
Follow-up: Why detached HEAD? *(Branch names can move; the SHA can't.)*

**26. Q: How do you apply a user's diff safely?**
Testing: patch semantics.
A: Write it to a temp file, `git apply --check` first, then `git apply`, only in the trial copy. If it doesn't apply cleanly the trial fails with git's message. Afterwards `git add -A && git diff --cached HEAD` records the actual change for every change type.
Follow-up: Could a diff write outside the repo? *(`git apply` refuses paths outside the working tree; the copy also skips symlinks.)*

**27. Q: How do you block dangerous repository URLs?**
Testing: input security.
A: `RepositoryUrlValidator` accepts only `https://host/path` (and `file:///` for demos), so `ext::`, `ssh`, `git@`, and `--upload-pack=…` are rejected; refs starting with `-` or containing `..` or spaces are rejected; `protocol.ext.allow=never` is set anyway.
Follow-up: Why allow `file://`? *(Local fixtures; it's a flag, `STACKTRIAL_ALLOW_LOCAL_REPOS`.)*

### JGit

**28. Q: Did you use JGit? Why or why not?**
Testing: honest trade-off reasoning.
A: No. I use the git CLI because I need exact `git apply` behaviour for user diffs, the CLI is what developers' diffs are produced with, and it avoids a dependency. The cost is that git must be installed and I parse text output. All git calls are in one class, `GitClient`, so swapping to JGit would touch only that class.
Follow-up: When would JGit be better? *(Running in a minimal container without git, or needing object-level access like walking commits.)*

**29. Q: What would change if you moved to JGit?**
Testing: abstraction.
A: Only `GitClient`: `Git.cloneRepository()`, `checkout().setName(sha)`, and `ApplyCommand` for patches; diff via `DiffFormatter`. Callers use `GitClient`'s methods and wouldn't notice.
Follow-up: Any behavioural risk? *(JGit's patch application differs from CLI in edge cases like fuzz and binary patches.)*

### Async processing

**30. Q: How does a trial run asynchronously?**
Testing: executors.
A: `TrialExecutionConfiguration` defines a `ThreadPoolTaskExecutor` with core/max = `max-concurrent-trials` (1 by default) and queue capacity 20. `TrialService` calls `executor.execute(() -> orchestrator.execute(id))`. On `TaskRejectedException` the trial is marked `FAILED: Trial queue is full`.
Follow-up: Why not `@Async`? *(Explicit executor makes the bound and rejection handling visible and testable.)*

**31. Q: What happens if the app restarts mid-trial?**
Testing: failure recovery.
A: The worker thread dies with the JVM. On `ApplicationReadyEvent`, `TrialRecovery` finds trials in non-terminal states and marks them `FAILED: Interrupted: StackTrial restarted while this trial was RUNNING`. Honest, not resumed.
Follow-up: How would you make it resumable? *(DB queue with `SELECT … FOR UPDATE SKIP LOCKED`, idempotent steps.)*

### Concurrency

**32. Q: Is anything shared between concurrent trials?**
Testing: shared state analysis.
A: Each trial has its own workspace directory, collected directory and containers (named with trial id and timestamp). Shared: the DB (row-level), the Maven cache volume, and `DockerContainerRunner.cacheVolumeReady`, which is `volatile` with double-checked locking around the one-time chown.
Follow-up: Can two Maven builds write the cache at once? *(Yes; Maven's local repo tolerates it reasonably but not perfectly; another reason for per-trial caches later.)*

**33. Q: Why limit concurrency to 1 by default?**
Testing: resource reasoning.
A: Each side can use 2 CPUs and 2 GB. On a laptop, two trials at once would contend for CPU and make durations noisy. It's configurable (`stacktrial.execution.max-concurrent-trials`).
Follow-up: Does concurrency affect the verdict? *(No; durations are informational only.)*

**34. Q: How does the UI see progress safely while a worker writes?**
Testing: visibility, transactions.
A: Each state change commits in its own transaction; the page reloads every 3 seconds and reads committed data through `TrialReportService`. No shared in-memory state between web and worker threads.
Follow-up: Race between `moveTo` and the final verdict? *(Single writer per trial: only the worker thread writes that trial.)*

### Testing

**35. Q: How do you test the comparison engine?**
Testing: unit test design.
A: `ComparisonEngineTest` builds `RunEvidence` by hand. A parameterized test covers every decision-table row; other tests cover rule interactions (regression needs a baseline pass, failing on both isn't a regression, duration never matters, identical output for identical input).
Follow-up: How do you know the table is complete? *(The last rule is a catch-all PASS, which is tested.)*

**36. Q: What do you mock, and what don't you?**
Testing: test-double discipline.
A: Only external boundaries: `FakeContainerRunner` for Docker in `TrialLifecycleIT`, a stub `LlmClient` in `ExplanationServiceIT`. Database, git, parsing and comparison are real. The comparison result is never mocked.
Follow-up: Then how do you test Docker itself? *(`DockerContainerRunnerIT` against the real engine, and the e2e test.)*

**37. Q: What did your end-to-end test catch that unit tests couldn't?**
Testing: test pyramid value.
A: The real interplay: the Surefire reports must be copied out of the container, the log must reach the parser, file ownership inside the container must let Maven write `target/`. Separately, a live UI check found that the progress page threw a Thymeleaf error (`#lists.indexOf` doesn't exist) while a trial was running; tests only rendered finished trials. I fixed it and added `progressPageRendersWhileTrialIsRunning`.
Follow-up: Why is e2e opt-in? *(It needs Docker and internet for Maven and takes minutes.)*

**38. Q: How do you test async code without flakiness?**
Testing: async testing.
A: `TrialLifecycleIT` posts, then polls `TrialReportService.report(id)` until `finished` with a deadline, instead of sleeping a fixed time.
Follow-up: Alternative? *(Awaitility; or a synchronous executor in tests.)*

### Testcontainers

**39. Q: How do integration tests get a database?**
Testing: Testcontainers usage.
A: `PostgresTestcontainer` is a `@TestConfiguration` with a `PostgreSQLContainer` from `pgvector/pgvector:pg16` annotated `@ServiceConnection`, so Spring Boot wires the datasource automatically. Flyway runs the real migration.
Follow-up: Why not H2? *(H2 has no pgvector, no `tsvector`, different SQL.)*

**40. Q: How long do the tests take and how do you keep them fast?**
Testing: pragmatism.
A: `mvn clean test` runs the 72 tests in about 1 minute 10 seconds, mostly container start-up. Spring's test-context cache reuses the application context, and with it the Postgres container, across test classes with the same configuration. The slow real-Docker e2e test is behind `-Pe2e`.
Follow-up: CI strategy? *(Unit + IT on every push; e2e nightly or on release branches.)*

### RAG

**41. Q: Explain your RAG pipeline.**
Testing: RAG fundamentals.
A: Curated Markdown is split per `##` section into chunks with stable ids (`KB-SB30-03`), stored in `knowledge_chunk`. With a key, chunks are embedded (768 dims). At explain time, the query is the change + regression findings + failure messages; retrieval is pgvector cosine similarity with a distance cut-off, or full-text search without a key. Retrieved chunks go into the prompt with ids, and Gemini must cite them.
Follow-up: Why curated summaries instead of the full migration guide? *(Small, precise chunks retrieve better and fit in the prompt; the trade-off is coverage.)*

**42. Q: How did you make retrieval good without embeddings?**
Testing: search engineering.
A: The first query included everything and returned noise. I narrowed it to what broke, split camelCase test names (`listsOrdersWithTrailingSlash` → trailing, slash), dropped generic stop words like "test" and "spring", kept 3-digit numbers like 404, and dropped hits below 35 % of the top rank. Scenario 1 now ranks `KB-SB30-03` first.
Follow-up: Where does it still fail? *(Scenario 4: a call to a non-existent method has no matching section, so the hits are weak. Honest limitation.)*

### Embeddings / vector search

**43. Q: Why 768 dimensions?**
Testing: embedding trade-offs.
A: `gemini-embedding-001` supports `outputDimensionality`; 768 is a good quality/size balance and matches `vector(768)` in the migration. The value is in config (`rag.embedding-dimensions`) and the migration together.
Follow-up: What if you change it? *(New migration for the column, re-embed everything; hash-based ingestion re-embeds when content changes, not dimensions, so you'd clear embeddings.)*

**44. Q: Cosine distance and HNSW: what are they?**
Testing: vector search basics.
A: Cosine distance is 1 − cosine similarity, comparing direction not length. HNSW is an approximate nearest-neighbour graph index; `vector_cosine_ops` makes it serve `<=>`. With 22 chunks it barely matters, but it's the right index if the corpus grows.
Follow-up: HNSW vs IVFFlat? *(HNSW: better recall/latency, slower build, no training step.)*

**45. Q: How do you decide a vector hit is irrelevant?**
Testing: thresholding.
A: `max-cosine-distance: 0.45`. Hits further away are dropped, so Gemini can be told nothing relevant was found rather than receive noise. The threshold isn't tuned on real Gemini embeddings yet; that's a documented pending step.
Follow-up: How would you tune it? *(Label a few scenarios with the right chunk, sweep the threshold, maximise precision.)*

### Gemini

**46. Q: How do you call Gemini?**
Testing: integration design.
A: `GeminiClient` uses Spring's `RestClient` against `generativelanguage.googleapis.com/v1beta`: `generateContent` with a system instruction and temperature 0.1, and `embedContent` with task type and output dimensionality. The key is a default header `x-goog-api-key`; connect and read timeouts are 60 s; errors are wrapped without the request so the key can't leak into logs.
Follow-up: Why not Spring AI? *(Two endpoints, no extra dependency, fewer moving parts.)*

**47. Q: Has the Gemini integration been verified?**
Testing: honesty.
A: The pipeline is verified with a stub LLM in an integration test and the no-key path in every live run. A real Gemini call has not been made yet because no key was available while building; it's one command to verify, documented in `GEMINI_FLOW.md`.
Follow-up: What could go wrong on first live call? *(Model name, quota, response shape; all end as a `FAILED` explanation with the reason, not a broken report.)*

### AI reliability

**48. Q: How do you stop the AI from deciding or changing the verdict?**
Testing: AI safety by design.
A: Structurally: the verdict is computed by `ComparisonEngine` and saved before `ExplanationService` runs, and that service has no code path that writes a verdict. The prompt also states the verdict is final. Even a malicious or confused answer can only produce text.
Follow-up: What if Gemini says "this is actually fine"? *(The text shows, but the verdict stays REGRESSION; the reader sees evidence that contradicts it.)*

**49. Q: How do you detect hallucinated citations?**
Testing: output validation.
A: `CitationValidator` extracts ids with a regex and sorts them into evidence ids that exist, chunk ids that were retrieved, and unknown ids. Unknown ids are stored and shown as a warning; no citations at all also gives a warning.
Follow-up: Can it detect a wrong claim with a valid citation? *(No; it checks traceability, not truth. The reader can follow the citation.)*

**50. Q: What happens when Gemini is down?**
Testing: graceful degradation.
A: `ExplanationService.explain` never throws: missing bean → `UNAVAILABLE`, exception → `FAILED` with the reason. The trial still completes with the full deterministic report and the retrieved documentation.
Follow-up: Retries? *(Not in V1; one attempt with timeouts. A retry with backoff for 429/5xx would be next.)*

### Security

**51. Q: What is the biggest security risk in this design?**
Testing: threat modelling.
A: The backend talks to the Docker Engine, which is root-equivalent on the host. I never mount the socket into runner containers, and the app binds to 127.0.0.1 with no auth because it's a single-user local tool. For a shared service, execution would move to separate disposable worker hosts so the web tier holds no Docker access.
Follow-up: Second biggest? *(Network access during builds and the shared Maven cache, which a malicious build could poison.)*

**52. Q: How do you protect against XXE?**
Testing: XML security.
A: Surefire XML and pom.xml come from untrusted repositories. Both parsers disable DOCTYPE declarations and use secure processing; `SurefireReportParserTest.rejectsXmlWithDoctype` covers it.
Follow-up: Why does that matter if it runs in a container? *(The parsing happens on the host.)*

**53. Q: What happens if a repository contains a symlink to `/etc/passwd`?**
Testing: path traversal.
A: `WorkspaceService.copyTree` and the tar writer copy regular files only and skip symlinks, so nothing outside the repository is copied or sent to the container. Copy-out ignores entries that resolve outside the target directory.
Follow-up: And `.git` hooks? *(A clone cannot bring hooks from the remote; `.git/hooks` comes from the local git template. `.git` is also never copied into the container.)*

### System design

**54. Q: How would you scale to 1,000 concurrent trials?**
Testing: scaling path.
A: Database (or Kafka) as the queue with `SKIP LOCKED` claiming; a fleet of worker hosts each with its own Docker or Kubernetes Jobs behind a new `ContainerRunner`; a shared read-only dependency cache or Nexus proxy; logs to object storage via `StorageService`; per-user rate limits. The parsers and comparison engine don't change.
Follow-up: What's the cost driver? *(Compute: about 2 CPUs × a few minutes per side.)*

**55. Q: How would you add Kafka later?**
Testing: event-driven design, outbox.
A: Keep the trial row as the source of truth. On POST, insert the trial and an outbox row in one transaction; a relay publishes `trial.created`. Workers claim with a conditional update `WHERE status = 'QUEUED'`, which makes duplicate deliveries harmless. `TrialOrchestrator.execute(id)` stays as it is.
Follow-up: Why the outbox? *(Avoid the dual-write problem between DB and broker.)*

**56. Q: How would you support Gradle?**
Testing: extensibility.
A: A `build.gradle` inspector next to `PomInspector`, Gradle `ChangeApplier`s, and a Gradle run script for the same `ContainerRunner`. Gradle writes the same JUnit XML, so the Surefire parser and the comparison engine are reused.
Follow-up: Hardest part? *(Editing Gradle build scripts safely: Groovy/Kotlin DSL and version catalogs.)*

### Architecture

**57. Q: Why a modular monolith instead of microservices?**
Testing: architectural judgement.
A: One developer, one deployable, one database, one machine. Microservices would add network calls, distributed failures and deployment overhead without a scaling need. Packages and interfaces give the same boundaries; `execution` is the natural first service to extract.
Follow-up: What would make you split it? *(Execution needing independent scaling or a different security zone.)*

**58. Q: Where are the seams, and what do they buy you?**
Testing: dependency inversion.
A: `ContainerRunner`, `ChangeApplier`, `LlmClient`/`EmbeddingClient`, `StorageService`. They let tests replace Docker and Gemini, and let me add a Kubernetes runner, a Gradle applier, another LLM or S3 storage by adding one class.
Follow-up: Any seam you'd add? *(A `RepositoryProvider` for private repos with tokens.)*

### Project-specific

**59. Q: Walk me through the Spring Boot 2.7 → 3.0 regression.**
Testing: depth on your own demo.
A: Fixture B has tests calling `/orders/` with a trailing slash. Spring Framework 6 (Boot 3.0) disabled trailing-slash matching, so those return 404. Baseline: 8 passed. Trial: 6 passed, 2 failed with `Status expected:<200> but was:<404>`. Rule 3 fires (baseline passed, trial failed) → REGRESSION, with two TEST_REGRESSION findings citing baseline-pass and trial-fail evidence. Retrieval ranks `KB-SB30-03`, the trailing-slash section, first.
Follow-up: Why didn't anything fail to compile? *(The fixture uses no `javax.*` APIs; the break is behavioural.)*

**60. Q: What would you improve next, and why?**
Testing: self-awareness, prioritisation.
A: First, a live Gemini verification and threshold tuning. Then two-phase execution (resolve dependencies with network, build/test with `--network none`), a per-trial Maven cache, re-running newly failing tests to label flaky ones, and an application startup + health check stage as extra evidence.
Follow-up: Which one reduces risk most? *(Network-off builds: they remove exfiltration from untrusted tests.)*

---

# WHAT I SHOULD LEARN FIRST

Goal: understand the whole architecture from about a dozen files, not memorise the project. Run the app once (RUNBOOK.md) before Day 1, so every file maps to something you've seen.

### DAY 1: the pipeline
Understand: the trial states, what happens in each, and where async starts.
Files:
- `trial/TrialService.java`
- `trial/TrialOrchestrator.java`
- `trial/TrialStore.java`
- `trial/TrialExecutionConfiguration.java`
Do: create scenario 1 in the UI and follow the log lines `[trial=…] [step=…]`.
Questions: 11, 30, 31, 8, 34.

### DAY 2: Docker execution
Understand: the in-container script, isolation controls, copy in/out, timeout, cleanup.
Files:
- `execution/MavenRunExecutor.java`
- `execution/DockerContainerRunner.java`
- `execution/RunnerImageResolver.java`
- test `execution/DockerContainerRunnerIT.java`
Do: run `docker events` while a trial runs; run `mvn test -Dtest=DockerContainerRunnerIT`.
Questions: 17–21, 51.

### DAY 3: evidence and the verdict
Understand: how files become facts, and how facts become a verdict.
Files:
- `evidence/EvidenceCollector.java`
- `evidence/SurefireReportParser.java` (skim), `evidence/MavenBuildLogParser.java` (skim)
- `comparison/ComparisonEngine.java`
- test `comparison/ComparisonEngineTest.java`
Do: open `docs/scenario-results/1-…/report.json` and match each finding to its evidence codes.
Questions: 2, 3, 35, 59, 52.

### DAY 4: workspace and changes
Understand: clone, ref → SHA, the two copies, the strategy pattern for changes, the recorded diff.
Files:
- `workspace/WorkspaceService.java`
- `workspace/GitClient.java`
- `workspace/change/ChangeApplier.java`, `ChangeService.java`, `SpringBootVersionChangeApplier.java`
Questions: 25–29, 5, 53.

### DAY 5: RAG and Gemini
Understand: ingestion, the query, two retrieval modes, the prompt rules, citation validation, fallbacks.
Files:
- `explanation/ExplanationService.java`
- `explanation/KnowledgeRetriever.java`
- `explanation/PromptBuilder.java`
- `explanation/CitationValidator.java`
- `explanation/GeminiClient.java` (skim)
Questions: 41–50.

### DAY 6: data, API, tests
Understand: tables and relationships, the API shape, what each test level proves.
Files:
- `db/migration/V1__init.sql`
- `web/api/TrialApiController.java`, `trial/TrialReport.java`
- tests `TrialLifecycleIT.java`, `RealDockerE2ETest.java`
Questions: 14–16, 10–13, 36–40.

### DAY 7: say it out loud
Practise sections A, B and the script below until you can give each without notes. Then answer questions 54–58 and 60 on a whiteboard.

---

# HOW TO EXPLAIN THE PROJECT

Use this wording, then adapt it to your own voice.

**Opening.** "StackTrial is a developer verification engine for Java and Spring Boot projects. It tells you, with evidence, whether a proposed change like a Spring Boot upgrade breaks your project, before you merge it."

**Problem.** "Upgrades are risky because failures appear in unexpected places: a changed default, a removed API, a transitive dependency. A failing test alone doesn't tell you much either, because it might have been failing already, or the environment might be broken."

**Solution.** "So StackTrial runs an experiment with a control group. It builds and tests the unchanged code as a baseline, applies the change to a separate copy, runs exactly the same build again, and compares the two."

**Architecture.** "It's a Spring Boot 3.5 modular monolith on Java 21 with PostgreSQL and pgvector. The API stores the trial and returns 202; a bounded worker pool runs a pipeline: prepare the workspace, run both sides, analyse, explain. External systems sit behind interfaces: Docker, Gemini and the change types."

**Docker isolation.** "Repository code never runs on my host. Each side runs `mvn verify` in a fresh container with memory, CPU and process limits, a non-root user, no Linux capabilities and a hard timeout. The code is copied in, results are copied out, and the container is always removed."

**Baseline and trial.** "Both sides use the same method, the same limits and the same script. The only difference is the change itself. A regression means a test passed in baseline and fails in trial, so pre-existing failures aren't blamed on the change."

**Deterministic evidence.** "I parse the real outputs: exit code, compile errors, Surefire XML, the dependency tree. A pure Java comparison engine numbers every fact and picks the verdict from an ordered decision table. Same inputs, same verdict, every time, and every rule is unit-tested."

**AI explanation.** "Only after the verdict is saved, I retrieve relevant migration documentation and ask Gemini to explain the failure, citing the evidence ids and documentation ids. I validate the citations and flag anything it invented. If Gemini is down, the report is still complete. The AI explains; it never decides."

**Result.** "For example, upgrading a Spring Boot 2.7 service to 3.0 shows two tests going from 200 to 404, verdict REGRESSION, and the top documentation hit is the trailing-slash change in the 3.0 migration guide. A safe dependency bump and a Java 17 to 21 upgrade both come back PASS, and a broken code change shows the exact compiler error."

**Close (if asked what you learned).** "Mostly that the hard part isn't calling an AI model; it's producing trustworthy evidence for it to explain. And a live end-to-end check found a UI bug my tests missed, which is why I test the real path, not just the units."
