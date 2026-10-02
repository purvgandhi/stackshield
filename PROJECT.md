# STACKTRIAL: project record

> **"Try the change before you merge it."**

| | |
|---|---|
| Owner | Aashu |
| Started | 2026-10-02 · ~30-day timebox |
| Mode | **Build mode** (Aashu, 2026-10-02): build the working MVP first, study it afterwards. No approval gates between phases. |
| Status | **MVP working end to end.** All 7 build phases implemented; see §6 for what was verified and how. |

The rule above everything else: **evidence first, AI second.** Execution produces facts → the comparison engine produces findings and the verdict → RAG retrieves documentation → Gemini explains. AI never decides the verdict.

---

## 1. Scope (V1, as set in build mode)

**In:** Java + Spring Boot + Maven repositories. Changes: dependency version, Spring Boot version, Java version, unified git diff. Execution: `mvn -B verify` in real Docker containers, baseline and trial. Evidence: exit code, build phase, compile errors, Surefire results, dependency tree, logs, duration. Verdicts: `PASS`, `REGRESSION`, `IMPROVEMENT`, `BOTH_FAILED`, `INCONCLUSIVE`. RAG over curated Spring Boot / Java migration knowledge with Gemini. Thymeleaf UI: dashboard, new trial, progress, report.

**Out (deliberately):** Kubernetes, microservices, Kafka, Redis, Gradle, other languages, cloud deployment, multi-tenancy, billing, complex authentication, performance benchmarking, AI-generated code or fixes, unnecessary libraries.

### Changes from the earlier master-prompt plan
Build mode narrowed the stack. Compared with the original master prompt: no Kafka, no Redis, no React (Thymeleaf instead), no Gradle, no Spring Security/JWT, no GitHub OAuth, no app startup / health / API / database-migration checks. Each is listed in README "Future improvements" or §7 below.

## 2. Architecture

Modular monolith under `backend/src/main/java/com/stacktrial/`:

| Module | Responsibility |
|---|---|
| `trial` | Trial entity + state machine, async orchestration, persistence, report read model |
| `workspace` | git clone / checkout / apply / diff, workspace copies, pom detection, `ChangeApplier` strategies |
| `execution` | `ContainerRunner` + `DockerContainerRunner` (docker-java), Maven run script, runner image choice, `StorageService` |
| `evidence` | Surefire XML, dependency tree and build log parsers → `RunEvidence` |
| `comparison` | Pure-Java comparison engine, findings, evidence codes, verdict decision table |
| `explanation` | Knowledge ingestion, pgvector / full-text retrieval, prompt, `LlmClient` + `EmbeddingClient` (Gemini), citation validation |
| `web` | REST API (`/api/trials`) and Thymeleaf pages |
| `common` | Typed configuration, log redaction, shared exceptions |

Full description: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); file by file: [docs/CODE_STRUCTURE.md](docs/CODE_STRUCTURE.md).

Repository layout:

```text
stacktrial/
├── backend/            Spring Boot app (pom.xml, src/main, src/test, config/ examples)
├── fixtures/           passing-upgrade/, breaking-upgrade/, diffs/, create-demo-repos.sh
├── scripts/            run-demo-scenarios.sh
├── docs/               13 guides (ARCHITECTURE, PROJECT_FLOW, …, INTERVIEW_GUIDE), scenario-results/, verification/, screenshots/
├── docker-compose.yml  PostgreSQL 16 + pgvector
├── .env.example  .gitignore  README.md  PROJECT.md  CLAUDE.md  BUILD_REPORT.md
```

## 3. Decisions log

| # | Decision | Why | Rejected alternative |
|---|---|---|---|
| D1 | Modular monolith | One developer, one deployable; boundaries via packages + interfaces | Microservices |
| D2 | Java 21 + Spring Boot **3.5.16** | Current LTS JDK; mature 3.x ecosystem the fixtures and docs target | Boot 4.x (newer, more migration churn for a 30-day build) |
| D3 | docker-java behind `ContainerRunner` | Typed Engine API, testable seam | Shelling out to the docker CLI |
| D4 | Copy workspace into the container as a tar, copy results out | No host bind mounts; works the same if the backend itself is containerised | Bind-mounting the workspace |
| D5 | Same limits/user/network/script for both sides from global config | Fairness: only the workspace (and Java image) differ | Per-run settings |
| D6 | Image chosen from each side's own `pom.xml` Java version | A Java upgrade automatically runs on the new JDK | Separate "image" field on the trial |
| D7 | Deterministic, table-driven comparison in pure Java | Reproducible, unit-testable, cannot hallucinate | LLM judging pass/fail |
| D8 | Bounded `ThreadPoolTaskExecutor` + DB state, restart marks unfinished trials FAILED | Enough for one host; honest about the limitation | Kafka / Redis (excluded in build mode) |
| D9 | git CLI via `ProcessBuilder` | Real `git apply` semantics; no extra library | JGit |
| D10 | Text-level, block-scoped pom edits | Diff shows only the intended line; formatting preserved | DOM re-serialisation |
| D11 | Gemini REST via Spring `RestClient` behind `LlmClient`/`EmbeddingClient` | Two endpoints, no extra dependency, easy to stub | Spring AI (more dependencies and API churn) |
| D12 | pgvector + PostgreSQL full-text fallback | One datastore; retrieval still real without a key | Separate vector DB; "no retrieval" without a key |
| D13 | Thymeleaf UI with meta-refresh progress | UI is priority 7; one build, no JS framework | React SPA |
| D14 | No authentication; server binds to 127.0.0.1 | Single-user local developer tool; "complex authentication" is out of scope | Spring Security + JWT |
| D15 | Mockito loaded as a `-javaagent` in Surefire | Required on JDK 21+ where self-attach is restricted | Relying on dynamic attach |

## 4. Data model

Flyway `V1__init.sql`: `trial`, `trial_run`, `test_result`, `evidence`, `finding`, `explanation`, `knowledge_chunk` (`vector(768)` + generated `tsvector`). UUID keys except evidence codes (`EVIDENCE-nnn`, unique per trial) and knowledge chunk IDs (`KB-XXXX-nn`).

## 5. API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/trials` | Create a trial → `202 Accepted` `{trialId, status: QUEUED, reportUrl, pageUrl}` |
| GET | `/api/trials` | Latest 50 trials |
| GET | `/api/trials/{id}` | Status + full report (comparison rows, findings, evidence, failed tests, explanation, applied diff) |
| GET | `/api/trials/{id}/runs/{BASELINE\|TRIAL}/log` | Redacted Maven build log |
| GET | `/api/trials/{id}/runs/{BASELINE\|TRIAL}/dependency-tree` | Dependency tree |
| GET | `/`, `/trials/new`, `/trials/{id}` | Dashboard, new trial form, progress / report page |

## 6. Verification status (2026-10-02, final build)

Full record: [BUILD_REPORT.md](BUILD_REPORT.md).

| What | How it was verified | Result |
|---|---|---|
| Unit + integration tests | `mvn clean test` and `mvn clean verify` (Testcontainers PostgreSQL 16 + pgvector, real Docker for `DockerContainerRunnerIT`) | 72 tests, 0 failures |
| Real Docker end to end | `mvn verify -Pe2e`: fixture B, Spring Boot 2.7.18 → 3.0.13 | 1 test, passes (`REGRESSION`) |
| Flyway + pgvector | Fresh compose database: `V1 init` applied, `vector 0.8.7`, 22 knowledge chunks | OK |
| Demo scenarios (live app, real Docker) | `scripts/run-demo-scenarios.sh`, outputs in `docs/scenario-results/` | 1 REGRESSION, 2 PASS, 3 PASS (17→21 images), 4 REGRESSION (COMPILE) |
| Public GitHub repository | spring-petclinic, Java 17 → 21 | PASS, 77 passed / 2 skipped both sides |
| Container cleanup | `docker events` during the scenarios; `DockerContainerRunnerIT` | 8 create / 8 destroy; no leftovers |
| REST API | `docs/verification/api-checks.txt` | All endpoints, 202/200/400/404 as documented |
| UI | Playwright drove the form → progress → report | OK after fixing the progress-page bug |
| **Gemini calls** | **Not verified against the live API** (no key); stub `LlmClient` in `ExplanationServiceIT` | Needs one live run with `GEMINI_API_KEY` |

Build-environment notes: Docker Hub rate-limited image pulls, so images came from `mirror.gcr.io` and were re-tagged; runner containers needed an HTTPS proxy, configured through `stacktrial.runner.extra-env` / `extra-binds` (see `backend/config/application-proxy.example.yml`). Neither is needed on a normal machine.

## 7. Known limitations and next steps

1. Run once with a real `GEMINI_API_KEY` and tune the prompt / distance threshold on the fixtures.
2. Startup + Actuator health + API checks (`stacktrial.yml`) as an extra evidence stage.
3. Two-phase execution: dependencies with network, build/test with `--network none`.
4. Per-trial or read-only Maven cache (shared cache can be poisoned by a malicious build).
5. Flaky-test detection by re-running newly failing tests.
6. Durable queue (DB `SKIP LOCKED` or Kafka) so restarts do not fail in-flight trials.
7. Gradle, private repositories (encrypted tokens).
8. Push the repository to GitHub (local git history exists; nothing pushed yet).
