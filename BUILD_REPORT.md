# StackTrial build report

Date: 2026-10-02. Everything marked VERIFIED below was actually executed in the build environment; the commands and outputs are saved in `docs/verification/` and `docs/scenario-results/`. Anything not executed is listed in section 19.

```text
BUILD STATUS   SUCCESS
Application    READY TO RUN
Tests          73 PASSED / 0 FAILED   (72 unit + integration, 1 real-Docker end-to-end)
Docker         VERIFIED   (real containers: isolation checked from inside, 5 scenarios, cleanup confirmed)
Database       VERIFIED   (PostgreSQL 16, Flyway V1 on an empty DB, pgvector 0.8.7)
E2E            VERIFIED   (Spring Boot 2.7.18 → 3.0.13 through two real containers → REGRESSION)
Gemini         NOT VERIFIED against the live API (no key available); implemented, stub-tested
```

## 1. What was actually implemented

| Area | Implementation (source exists) |
|---|---|
| Trial lifecycle | `trial/TrialService`, `TrialOrchestrator`, `TrialStore`, `TrialRecovery`; states QUEUED → PREPARING → RUNNING → ANALYZING → EXPLAINING → COMPLETED / FAILED; bounded async executor |
| Git operations | `workspace/GitClient` (git CLI): clone, ref → SHA, detached checkout, `git apply --check`/`apply`, diff vs HEAD |
| Repository understanding | `workspace/PomInspector`: Java and Spring Boot versions from `pom.xml` |
| Change types | `SPRING_BOOT_VERSION`, `JAVA_VERSION`, `DEPENDENCY_VERSION` (text-level `PomEditor`), `GIT_DIFF` |
| Docker execution | `execution/DockerContainerRunner` (docker-java): limits, non-root, CapDrop ALL, no-new-privileges, timeout + kill, tar copy in/out, force-remove; `MavenRunExecutor` runs the same script for both sides; image per Java version |
| Evidence | `evidence/*`: build log (phase, compile errors with javac details), Surefire XML (XXE-safe), dependency tree |
| Comparison | `comparison/ComparisonEngine`: evidence codes, 10 finding types, 8-row ordered verdict table; pure Java |
| RAG | 22 curated chunks; ingestion with hash-based re-embedding; pgvector cosine search; PostgreSQL full-text fallback |
| Gemini | `explanation/GeminiClient` (`generateContent`, `embedContent`), grounded prompt, citation validation, UNAVAILABLE / FAILED fallbacks |
| Persistence | Flyway `V1__init.sql`: 7 tables; JPA entities + JDBC for vector/FTS queries |
| REST API | 5 endpoints under `/api/trials` + Actuator health; RFC 9457 errors |
| UI | Thymeleaf: dashboard, new-trial form, auto-refreshing progress page, report (comparison, findings, evidence, failed tests, explanation, citations, diff, logs) |
| Security | URL/ref validation, `ext::` disabled, symlinks skipped, XXE disabled, log redaction, 127.0.0.1 binding, no secrets in git |

Not implemented (and not claimed): authentication, JGit (git CLI is used instead), application startup/health checks of the target app, flaky-test re-runs, Gradle, private repositories.

## 2. Files created in this final pass

- `backend/src/test/java/com/stacktrial/execution/DockerContainerRunnerIT.java` (real-Docker isolation/timeout/cleanup test)
- `scripts/run-demo-scenarios.sh`, `fixtures/diffs/compile-break.diff`
- `docs/PROJECT_FLOW.md`, `CODE_STRUCTURE.md`, `DATABASE.md`, `API.md`, `DOCKER_FLOW.md`, `EVIDENCE_ENGINE.md`, `COMPARISON_ENGINE.md`, `RAG_FLOW.md`, `GEMINI_FLOW.md`, `TESTING.md`, `RUNBOOK.md`, `INTERVIEW_GUIDE.md`
- `docs/scenario-results/` (5 scenarios + `docker-events.log`), `docs/verification/` (Maven logs, API checks), `docs/screenshots/progress.png`, `report-full.png`
- `BUILD_REPORT.md`

The rest of the application (104 main + 16 earlier test Java files, templates, migration, fixtures) was created in the two earlier build commits (`7182d98`, `a8bc75e`).

## 3. Files modified in this final pass

- `backend/src/main/resources/templates/progress.html`: **bug fix**. The page used `#lists.indexOf`, which does not exist in Thymeleaf, so every running trial's page returned HTTP 500. Found by driving the UI in a browser.
- `backend/src/test/.../trial/TrialLifecycleIT.java`: progress-page test (failed before the fix), list and dependency-tree endpoint checks.
- `backend/src/test/.../explanation/ExplanationServiceIT.java`: report API and page render a generated explanation.
- `backend/src/test/.../explanation/ExplanationUnitTest.java`: relative-rank cut-off test.
- `docs/architecture.md` → `docs/ARCHITECTURE.md` (decisions table added); `docs/demo.md` merged into `RUNBOOK.md`; `docs/interview-notes.md` merged into `INTERVIEW_GUIDE.md`.
- `README.md` (rewritten with the 11 exact steps), `PROJECT.md` (§2 layout, §6 verification), `CLAUDE.md` (doc references, commands), screenshots.

## 4. Final project tree

```text
stacktrial/
├── README.md  BUILD_REPORT.md  PROJECT.md  CLAUDE.md  .env.example  .gitignore  docker-compose.yml
├── backend/
│   ├── pom.xml                         Spring Boot 3.5.16, Java 21
│   ├── config/application-proxy.example.yml
│   └── src/
│       ├── main/java/com/stacktrial/   web/ trial/ workspace/ (change/) execution/ evidence/ comparison/ explanation/ common/
│       ├── main/resources/             application.yml, db/migration/V1__init.sql, knowledge/*.md, templates/*.html, static/css/app.css
│       └── test/java/com/stacktrial/   13 test classes + 3 helpers
├── fixtures/
│   ├── breaking-upgrade/               Spring Boot 2.7.18 orders API, 8 tests
│   ├── passing-upgrade/                Spring Boot 3.2.12 greeting API, 5 tests
│   ├── diffs/compile-break.diff
│   └── create-demo-repos.sh
├── scripts/run-demo-scenarios.sh
└── docs/
    ├── PROJECT_FLOW.md ARCHITECTURE.md CODE_STRUCTURE.md DATABASE.md API.md DOCKER_FLOW.md
    ├── EVIDENCE_ENGINE.md COMPARISON_ENGINE.md RAG_FLOW.md GEMINI_FLOW.md TESTING.md RUNBOOK.md INTERVIEW_GUIDE.md
    ├── scenario-results/   1-spring-boot-2.7-to-3.0/ 2-safe-dependency-upgrade/ 3-java-17-to-21/ 4-broken-code-change/ 5-github-petclinic-java-21/ docker-events.log
    ├── verification/       mvn-clean-test.log mvn-verify.log mvn-verify-e2e.log api-checks.txt
    └── screenshots/        dashboard.png new-trial.png progress.png report.png report-full.png
```

## 5. Commands to run

```bash
cp .env.example .env && set -a && . ./.env && set +a     # optional; add GEMINI_API_KEY here
docker compose up -d                                       # PostgreSQL 16 + pgvector
cd backend
mvn clean verify                                           # build + 72 tests (Docker required)
java -jar target/stacktrial-0.1.0-SNAPSHOT.jar             # http://localhost:8080
# second terminal, repository root:
sh fixtures/create-demo-repos.sh
sh scripts/run-demo-scenarios.sh                           # the 4 scenarios (needs curl + jq)
# optional:
cd backend && mvn verify -Pe2e                             # real-Docker end-to-end test
```

Details: `docs/RUNBOOK.md`.

## 6. Environment variables

`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `GEMINI_API_KEY`, `GEMINI_CHAT_MODEL`, `GEMINI_EMBEDDING_MODEL`, `STACKTRIAL_DATA_DIR`, `STACKTRIAL_RUN_TIMEOUT`, `STACKTRIAL_RUNNER_NETWORK`, `STACKTRIAL_ALLOW_LOCAL_REPOS`, `PORT`, `SERVER_ADDRESS`, and `DOCKER_HOST` (read by docker-java). Defaults and meaning: `docs/RUNBOOK.md` §2. All have working defaults; only `GEMINI_API_KEY` is needed for the AI explanation.

## 7. Database setup

`docker compose up -d` starts `pgvector/pgvector:pg16` on `127.0.0.1:5432` (db/user/password `stacktrial`). Flyway creates the schema on first app start, including `CREATE EXTENSION vector`. VERIFIED on an empty volume: `flyway_schema_history` version 1 `init` success, extension `vector 0.8.7`, 22 `knowledge_chunk` rows. Schema and relationships: `docs/DATABASE.md`.

## 8. Docker setup

The backend runs on the host and talks to the Docker Engine through `DOCKER_HOST` (default `/var/run/docker.sock`). Runner images `maven:3.9-eclipse-temurin-17` and `-21` are pulled automatically; the named volume `stacktrial-m2-cache` holds the Maven cache. Runner containers need internet access for Maven. Controls and trade-offs: `docs/DOCKER_FLOW.md`.

## 9. Tests executed

| Command | Result | Log |
|---|---|---|
| `mvn -B clean test` | 72 run, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS (1 min 09 s) | `docs/verification/mvn-clean-test.log` |
| `mvn -B clean verify` | 72 run, 0 failures, 0 errors, 0 skipped, jar built, BUILD SUCCESS | `docs/verification/mvn-verify.log` |
| `mvn -B verify -Pe2e` | 1 run (`RealDockerE2ETest`), 0 failures, BUILD SUCCESS (48 s) | `docs/verification/mvn-verify-e2e.log` |

Types: unit (comparison, parsers, change appliers with real git, validators, redaction, retrieval helpers), database integration (Testcontainers pgvector), API/UI integration (MockMvc), Docker integration (`DockerContainerRunnerIT`, real engine), end to end (`RealDockerE2ETest`). Per-class breakdown: `docs/TESTING.md`.

## 10. Exact test count

**73 tests: 72 in the default suite + 1 end-to-end. 73 passed, 0 failed.**

## 11. Real Docker scenarios executed

Run with `scripts/run-demo-scenarios.sh` against the running app (real git clone, two real containers per trial, real `mvn verify`), plus one public GitHub repository.

## 12. Results of each scenario

| # | Repository / change | Baseline | Trial | Verdict | Key evidence |
|---|---|---|---|---|---|
| 1 | `breaking-upgrade`, Spring Boot 2.7.18 → 3.0.13 | SUCCESS, 8 passed | FAILURE in TEST, 6 passed / 2 failed | **REGRESSION** | `OrderControllerTest#listsOrdersWithTrailingSlash` and `#returnsOneOrderWithTrailingSlash`: `Status expected:<200> but was:<404>`; 49 dependency changes; top doc `KB-SB30-03` |
| 2 | `passing-upgrade`, commons-lang3 3.13.0 → 3.17.0 | SUCCESS, 5 passed | SUCCESS, 5 passed | **PASS** | one `DEPENDENCY_CHANGED` finding |
| 3 | `passing-upgrade`, Java 17 → 21 | SUCCESS on `temurin-17` | SUCCESS on `temurin-21` | **PASS** | images differ per side as designed |
| 4 | `passing-upgrade`, git diff calling `StringUtils.truncateMiddle` | SUCCESS, 5 passed | FAILURE in COMPILE, no tests ran | **REGRESSION** | `GreetingService.java:[11,43] cannot find symbol \| symbol: method truncateMiddle(java.lang.String,int) \| location: class org.apache.commons.lang3.StringUtils` |
| 5 | `https://github.com/spring-projects/spring-petclinic` @ `500158f`, Java 17 → 21 | SUCCESS, 77 passed / 2 skipped | SUCCESS, 77 passed / 2 skipped | **PASS** | real HTTPS clone; Spring Boot 4.1.0 project |

Container lifecycle during scenarios 1–4: 8 `create`, 8 `start`, 8 `die`, 8 `destroy` events; no StackTrial container left afterwards; `data/workspaces/` empty. Outputs: `docs/scenario-results/<scenario>/{request.json, report.json, baseline-build.log, trial-build.log, *-dependency-tree.txt}`.

Re-running a scenario gives the same verdict, findings and evidence codes; only durations differ (scenario 1 was run three times: twice via the script, once through the UI, always `REGRESSION` with the same two failing tests).

## 13. API endpoints verified

Against the running app (`docs/verification/api-checks.txt`): `POST /api/trials` → 202 with `Location`; `GET /api/trials` → 200; `GET /api/trials/{id}` → 200 full report; `GET …/runs/TRIAL/log` → 200; `GET …/runs/BASELINE/dependency-tree` → 200; invalid URL → 400; missing `groupId` → 400; unknown id → 404; invalid side → 400; `GET /actuator/health` → `UP`. Also covered by MockMvc in `TrialLifecycleIT` and `ExplanationServiceIT`.

## 14. UI verified

Headless Chromium (Playwright) drove the real UI: dashboard → New trial form filled and submitted → progress page (RUNNING, "Baseline finished: SUCCESS 8 passed") → report with REGRESSION, comparison table, findings, evidence codes, failed tests, `KB-SB30-03`, "Why did it fail?" section and the no-key note. Screenshots: `docs/screenshots/`. This check found and led to fixing the progress-page bug (section 3).

## 15. Gemini status

**NOT VERIFIED against the live Gemini API.** No `GEMINI_API_KEY` was available in the build environment, so no real request was made.
Verified instead: the explanation pipeline with a stub `LlmClient` (retrieval → prompt → citation validation incl. an invented id → stored → shown in API and page), redaction of secrets before prompting, and the no-key path (`UNAVAILABLE` + full-text documentation) in every live scenario. Vector retrieval verified with fixed vectors in pgvector, not with real Gemini embeddings. How to verify: `docs/GEMINI_FLOW.md` → "Verify it yourself".

## 16. Known limitations

- Maven projects with a root `pom.xml` only; no Gradle.
- Spring Boot changes need `spring-boot-starter-parent` or a `spring-boot.version` property; dependency changes need the dependency declared in the root pom; otherwise use a git diff.
- Runner images for Java 17 and 21 only (configurable).
- Verification is `mvn verify`: no start-up of the target application, no HTTP/health checks.
- Runner containers have network access for the whole build; the Maven cache volume is shared between runs.
- In-memory worker pool: a restart marks running trials `FAILED (Interrupted …)`; one trial at a time by default.
- No flaky-test detection; a flaky test can appear as a regression.
- Full-text retrieval is lexical: scenario 4's documentation hits are weak because the corpus has no section for that kind of error.
- Single-user local tool: no authentication, bound to 127.0.0.1; the backend needs Docker socket access.

## 17. Remaining TODOs

1. Run once with a real `GEMINI_API_KEY`; tune `rag.max-cosine-distance` on real embeddings.
2. Two-phase execution (dependencies with network, build/test with `--network none`).
3. Per-trial or read-only Maven cache.
4. Re-run newly failing tests to label flaky ones.
5. Target-application start-up + health check as an extra evidence stage.
6. Durable queue (`SELECT … FOR UPDATE SKIP LOCKED`) so restarts don't fail in-flight trials.
7. Push the repository to GitHub (waiting for the owner's go-ahead).

## 18. Git commit hash

Local repository on branch `main`, nothing pushed. Final verified build: **`c9ad549`** (earlier: `a8bc75e`, `7182d98`). The commit after it only records this hash.

## 19. What was NOT verified

- **Live Gemini calls** (text generation and embeddings), and therefore vector retrieval with real embeddings.
- Running on macOS / Docker Desktop or Windows/WSL2: everything ran on Linux.
- The build environment needed workarounds that a normal machine does not: Docker started by hand, images pulled from `mirror.gcr.io` (Docker Hub rate limit), and runner containers on `network-mode: host` with an HTTPS proxy and truststore supplied through `stacktrial.runner.extra-env` / `extra-binds`. The default `bridge` network path was therefore not exercised end to end here.
- The CPU limit (`NanoCPUs`) is set but was not read back inside a container (memory, PIDs, user, capabilities and no-new-privileges were).
- Run timeout was verified with a 3-second limit in `DockerContainerRunnerIT`, not with a real Maven build exceeding 15 minutes.
- Concurrency above 1 trial at a time.
- Scenarios 1–5 ran on the build just before the progress-page template fix; the backend code they exercised is unchanged since, and scenario 1 was run again through the UI after the fix.
