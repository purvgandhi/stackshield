# StackTrial architecture

Related: [PROJECT_FLOW.md](PROJECT_FLOW.md) (request walk-through), [CODE_STRUCTURE.md](CODE_STRUCTURE.md) (file by file), [DATABASE.md](DATABASE.md), [DOCKER_FLOW.md](DOCKER_FLOW.md), [RAG_FLOW.md](RAG_FLOW.md).

## 1. Shape

StackTrial is a **modular monolith**: a single Spring Boot 3.5 application (Java 21) with clear package boundaries, one PostgreSQL 16 database (with pgvector), and the host's Docker Engine.

```text
                    ┌──────────────────────────── StackTrial (Spring Boot) ────────────────────────────┐
 Browser / curl ───▶│ web          TrialApiController (REST), TrialPageController (Thymeleaf)          │
                    │   │                                                                              │
                    │   ▼                                                                              │
                    │ trial        TrialService ──submit──▶ trialExecutor (bounded thread pool)         │
                    │              TrialOrchestrator: PREPARING → RUNNING → ANALYZING → EXPLAINING      │
                    │   │      │            │                │                 │                       │
                    │   ▼      ▼            ▼                ▼                 ▼                       │
                    │ workspace  execution          evidence         comparison        explanation      │
                    │ GitClient  MavenRunExecutor   Surefire parser  ComparisonEngine  KnowledgeRetriever│
                    │ PomInspector ContainerRunner  DepTree parser   (pure Java)       PromptBuilder     │
                    │ ChangeApplier×4  │            BuildLog parser                    LlmClient ────────┼──▶ Gemini API
                    └──────────────────┼───────────────────────────────────────────────────────────────┘
                                       ▼                                   ▲
                               Docker Engine (docker-java)                 │ JPA + JDBC
                         [baseline container]  [trial container]     PostgreSQL 16 + pgvector
```

Dependency direction: `web → trial → {workspace, execution, evidence, comparison, explanation}`. `comparison` depends only on the `evidence` records and nothing else, so it can be tested in isolation.

## 2. Trial lifecycle

```text
POST /api/trials ──▶ validate URL/ref/change ──▶ INSERT trial (QUEUED) ──▶ executor.execute(...) ──▶ 202 Accepted

worker thread (TrialOrchestrator.execute):
  PREPARING   git clone --no-checkout; resolve ref → commit SHA; checkout --detach
              copy → baseline/  and  trial/          (symlinks skipped)
              ChangeApplier.apply(trial/) ; git diff (stored as "applied diff")
              PomInspector on each side → Java version → runner image per side
  RUNNING     MavenRunExecutor.run(BASELINE) ─▶ DockerContainerRunner
              MavenRunExecutor.run(TRIAL)    ─▶ DockerContainerRunner
              each: EvidenceCollector → RunEvidence ; save trial_run + test_result rows
  ANALYZING   ComparisonEngine.compare(baseline, trial) → findings, evidence codes, verdict ; save
  EXPLAINING  ExplanationService.explain(...)   (never throws, never touches the verdict)
  COMPLETED
on expected errors (bad repo, unsupported project, invalid diff, Docker failure) → FAILED + failure_reason
finally: delete workspaces and collected files
on application start: every non-terminal trial → FAILED ("Interrupted: StackTrial restarted …")
```

Each status change is its own short transaction (`TrialStore`), so the UI can poll progress while the worker runs.

## 3. Docker execution

`DockerContainerRunner` is the only class that talks to Docker.

| Step | What happens |
|---|---|
| Image | `inspectImage`; pull if missing (`maven:3.9-eclipse-temurin-{17,21}`) |
| Cache volume | Named volume `stacktrial-m2-cache` mounted at `/m2`; chowned once to the runner user by a short-lived root container that has only `CHOWN` |
| Create | user `1000:1000`, workdir `/workspace`, memory + memory-swap limit, NanoCPUs, PIDs limit, `CapDrop ALL`, `no-new-privileges`, not privileged, configured network |
| Copy in | Workspace written as a tar (owned by uid 1000, `.git` and symlinks excluded) and copied to `/workspace` with the Engine API: no bind mount of host paths |
| Run | `sh -c` script: `mvn verify` → `.stacktrial/build.log`; gather `TEST-*.xml`; `mvn dependency:tree` → `.stacktrial/dependency-tree.txt`; exit with the verify exit code |
| Wait | `waitContainer().awaitStatusCode(timeout)`; on timeout the container is killed and the run is marked `TIMEOUT` |
| Copy out | `/workspace/.stacktrial` copied back as a tar; entries escaping the target directory are ignored |
| Cleanup | `removeContainer(force=true)` in `finally` |

Fairness: resource limits, user, network, timeout and the script are global configuration, not per-run parameters. The only input that differs between baseline and trial is the workspace (and the image when the change is a Java version, since the image is derived from each side's own `pom.xml`).

## 4. Evidence and comparison

```text
build.log ──────────▶ MavenBuildLogParser   → phase reached, compile errors (+ javac symbol details), failing goal
TEST-*.xml ─────────▶ SurefireReportParser  → class, name, outcome, message, type, stack trace, duration
dependency-tree.txt ▶ DependencyTreeParser  → groupId:artifactId → version, scope
exit code, timeout  ▶ BuildOutcome SUCCESS | FAILURE | TIMEOUT
                         └──────▶ RunEvidence (one per side)
```

`ComparisonEngine.compare(baseline, trial)`:

1. Records `EVIDENCE-001` (baseline run) and `EVIDENCE-002` (trial run).
2. Build findings: `BUILD_REGRESSION`, `BUILD_FIXED`, `BUILD_FAILED_BOTH`, `RUN_TIMEOUT` (with compile errors as evidence).
3. Test findings per test key `class#name`, iterated in sorted order: `TEST_REGRESSION` (baseline PASSED and trial FAILED/ERROR), `TEST_FIXED`, `TEST_STILL_FAILING`, `TEST_MISSING` (only when the trial actually ran tests), `NO_TESTS`.
4. Dependency findings: version changes, additions and removals (`INFO`).
5. Verdict from the ordered `VERDICT_RULES` table (see [COMPARISON_ENGINE.md](COMPARISON_ENGINE.md)).

Because inputs are sorted and the engine has no clock, randomness or I/O, the same two inputs always give the same findings, the same evidence codes and the same verdict. This is unit-tested.

## 5. Data model (Flyway `V1__init.sql`)

| Table | Content |
|---|---|
| `trial` | repo, ref, resolved commit, change type + JSON spec, applied diff, Java/Boot versions per side, status, current step, verdict, reason, failure reason, timestamps |
| `trial_run` | one row per side: image, outcome, exit code, timeout flag, phase, duration, test counts, compile error count, dependency count, failure summary, storage keys for log and tree |
| `test_result` | every test case of every run |
| `evidence` | `EVIDENCE-nnn` facts per trial (unique per trial) |
| `finding` | ordered findings with JSON lists of baseline/trial evidence codes |
| `explanation` | status (`GENERATED`, `NOT_NEEDED`, `UNAVAILABLE`, `FAILED`), model, retrieval method, text, retrieved chunk IDs, validated citations, unknown citations |
| `knowledge_chunk` | curated documentation chunks, `vector(768)` embedding (HNSW, cosine), generated `tsvector` (GIN) |

Logs and dependency trees are files behind `StorageService` (`LocalStorageService` under `data/artifacts`), referenced by key from `trial_run`.

## 6. RAG

```text
knowledge/*.md ─▶ KnowledgeLoader (split on "## ") ─▶ knowledge_chunk (upsert, hash-based re-embed)
                                                     └─ Gemini embedContent (RETRIEVAL_DOCUMENT, 768d) if key set

query = change + non-INFO finding titles + failure messages
  ├─ key set and all chunks embedded → embed query (RETRIEVAL_QUERY) → ORDER BY embedding <=> query, cosine distance ≤ 0.45
  └─ otherwise → to_tsquery(term1 | term2 | …) ranked by ts_rank, keeping hits ≥ 35 % of the best rank

prompt = system rules + VERDICT (final) + FINDINGS + EVIDENCE (redacted) + KNOWLEDGE ─▶ Gemini generateContent (temperature 0.1)
answer ─▶ CitationValidator (known evidence / known chunks / unknown) ─▶ explanation row
```

## 7. Failure handling

| Situation | Result |
|---|---|
| Invalid URL, ref or change fields | `400` problem detail, no trial created |
| Clone fails, ref not found | trial `FAILED`: "Repository unavailable: …" |
| No `pom.xml`, Java version without a runner image | trial `FAILED` with the reason |
| Change cannot be applied (diff does not apply, dependency not declared) | trial `FAILED` with the reason |
| Docker unreachable or image pull fails | trial `FAILED`: "Docker execution failed: …" |
| Build or tests fail | normal evidence, reflected in findings and verdict |
| Run exceeds the timeout | container killed, run `TIMEOUT`, verdict `INCONCLUSIVE` |
| Gemini missing or failing | explanation `UNAVAILABLE` / `FAILED`; report unaffected |
| Worker queue full | trial `FAILED`: "Trial queue is full" |
| Application restarts mid-trial | trial `FAILED`: "Interrupted …" at next startup |

## 8. Scaling path

V1 runs trials on a bounded in-process pool (default 1 at a time) on one host. To scale:

1. Make the database the queue: workers claim `QUEUED` trials with `SELECT … FOR UPDATE SKIP LOCKED`, so several app instances can share work and a restart no longer loses trials.
2. Move execution to dedicated worker hosts (each with its own Docker Engine), which also removes the Docker socket from the web tier.
3. Replace polling of a table with Kafka (`trial.created`) once there are many producers/consumers; the trial row stays the source of truth.
4. Swap `LocalStorageService` for object storage; swap `DockerContainerRunner` for a Kubernetes Job runner behind the same `ContainerRunner` interface.

## 9. Key decisions

| Decision | Why | Rejected alternative |
|---|---|---|
| Modular monolith | One developer, one deployable; boundaries come from packages and interfaces | Microservices |
| Java 21 + Spring Boot 3.5 | Current LTS JDK, mature Spring Boot line | Boot 4.x (more churn for a 30-day build) |
| docker-java behind `ContainerRunner` | Typed Engine API; the seam lets tests replace Docker | Shelling out to the `docker` CLI |
| Copy the workspace in/out as tar | No host bind mounts; the container cannot touch host files | Bind-mounting the workspace |
| Global runner settings | Baseline and trial cannot get different limits, so the comparison is fair | Per-run settings |
| Runner image from each side's `pom.xml` | A Java upgrade automatically runs on the new JDK | Separate "image" field |
| Pure-Java, table-driven comparison | Reproducible and unit-testable; cannot hallucinate | An LLM judging pass/fail |
| Bounded thread pool + DB state | Enough for one host; restart marks unfinished trials `FAILED` | Kafka / Redis |
| `git` CLI via `ProcessBuilder` | Real `git apply` semantics and no extra library | JGit (not used) |
| Text-level, block-scoped pom edits | The diff shows only the intended line; formatting is preserved | DOM re-serialisation |
| Gemini REST via `RestClient` | Two endpoints; no extra dependency; easy to stub | Spring AI |
| pgvector + full-text fallback | One datastore; retrieval still real without a key | A separate vector DB |
| Thymeleaf with auto-refresh | One build, no JS framework | React SPA |
| No auth, bind to 127.0.0.1 | Single-user local tool | Spring Security + JWT |
