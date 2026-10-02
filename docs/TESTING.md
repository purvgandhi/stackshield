# Testing

```bash
cd backend
mvn clean test            # unit + integration tests (needs Docker: Testcontainers + DockerContainerRunnerIT)
mvn verify                # same tests through the full Maven lifecycle, plus packaging
mvn verify -Pe2e          # only the real end-to-end test: real git, two real containers, real mvn verify
```

Last run on 2026-10-02 (logs in [verification/](verification/)):

| Command | Result |
|---|---|
| `mvn clean test` | **72 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn clean verify` | **72 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS |
| `mvn verify -Pe2e` | **1 test, 0 failures** (`RealDockerE2ETest`, about 1 minute) |

Total: **73 tests**, all passing.

## What is real and what is replaced

| Boundary | In unit tests | In integration tests | In the e2e test |
|---|---|---|---|
| PostgreSQL + pgvector + Flyway | n/a | **real** (Testcontainers `pgvector/pgvector:pg16`) | **real** |
| git | real (`ChangeApplierTest` runs `git apply`) | **real** clone of a fixture repo | **real** |
| Docker | n/a | **real** in `DockerContainerRunnerIT`; replaced by `FakeContainerRunner` in `TrialLifecycleIT` only | **real** |
| Maven inside the container | n/a | fake Surefire XML/log from `FakeContainerRunner` in `TrialLifecycleIT` | **real** `mvn verify` |
| Comparison engine | real | **real, never mocked** | **real** |
| Gemini | n/a | stub `LlmClient` in `ExplanationServiceIT` | not configured (UNAVAILABLE path) |

Test doubles exist only at external boundaries (Docker in one IT, Gemini in one IT). The comparison result is never mocked.

## Test classes (`backend/src/test/java/com/stacktrial/`)

| Class | Type | Tests | What it proves |
|---|---|---|---|
| `comparison/ComparisonEngineTest` | unit | 18 | Every decision-table row (parameterized), regression needs a baseline pass, failing-on-both is not a regression, compile errors become evidence, dependency diff is informational, duration never affects the verdict, identical output for identical input, catch-all PASS rule |
| `evidence/SurefireReportParserTest` | unit | 3 | Outcomes, messages, durations; reads only `TEST-*.xml`; rejects DOCTYPE (XXE) |
| `evidence/MavenBuildLogParserTest` | unit | 5 | Compile errors + phase; javac symbol/location details and dedup; TEST, TEST_COMPILE, dependency failures; success → VERIFY |
| `evidence/DependencyTreeParserTest` | unit | 2 | Tree with classifiers/annotations; empty input |
| `workspace/change/ChangeApplierTest` | unit (real git) | 11 | Spring Boot parent edit only, same version rejected, dependency via property / literal / managed, undeclared dependency rejected, version validation, Java property, `git apply` success and failure, routing by type |
| `workspace/PomInspectorTest` | unit | 3 | Spring Boot and Java detection, legacy `1.8` notation, non-Maven repo rejected |
| `workspace/RepositoryUrlValidatorTest` | unit | 11 | https and file URLs accepted; `--upload-pack`, `ext::`, scp-style ssh and other URLs rejected; option-like and malformed refs rejected |
| `common/LogRedactorTest` | unit | 3 | Token formats, key=value secrets and URL credentials masked; ordinary build output untouched |
| `explanation/ExplanationUnitTest` | unit | 5 | Citation validation, knowledge chunk ids, bundled KB loads, search terms, relative-rank cut-off |
| `explanation/KnowledgeRepositoryIT` | integration (DB) | 3 | Ingestion at startup, full-text finds `KB-SB30-03`, pgvector cosine ranking + cut-off |
| `explanation/ExplanationServiceIT` | integration (DB, stub LLM) | 2 | Grounded prompt, citation validation, redaction, report API/page show the explanation; PASS → NOT_NEEDED |
| `trial/TrialLifecycleIT` | integration (DB, git, MockMvc; Docker faked) | 4 | `POST /api/trials` → async → REGRESSION report; log, dependency-tree, list endpoints; dashboard and report pages; progress page while running; 400/404 errors; invalid diff → FAILED |
| `execution/DockerContainerRunnerIT` | integration (**real Docker**) | 2 | Inside a real container: uid 1000, no capabilities, NoNewPrivs, memory and PIDs limits, file copy in/out, `.git` not copied; timeout kill; container removed afterwards |
| `trial/RealDockerE2ETest` | end to end (`@Tag("e2e")`) | 1 | Fixture B Spring Boot 2.7.18 → 3.0.13 through real Docker: baseline SUCCESS, trial 2 failures, verdict REGRESSION, two trailing-slash TEST_REGRESSION findings |

Helpers: `PostgresTestcontainer` (shared pgvector container + `@ServiceConnection`), `FixtureRepository` (turns `fixtures/<name>` into a git repo), `FakeContainerRunner`.

## Beyond automated tests: live verification

These were run by hand against the running application and saved:

| Check | Evidence |
|---|---|
| 4 demo scenarios + a public GitHub repo, real Docker | [scenario-results/](scenario-results/) (`report.json`, both build logs, both dependency trees) |
| Containers created and removed | [scenario-results/docker-events.log](scenario-results/docker-events.log): 8 create / 8 destroy for 4 trials |
| Every REST endpoint, incl. 400 and 404 | [verification/api-checks.txt](verification/api-checks.txt) |
| UI: submit the form, progress page, report | Playwright (headless Chromium) drove the form; screenshots in [screenshots/](screenshots/) |
| Flyway on an empty database, pgvector installed | `flyway_schema_history` row `1 init success=t`, `vector 0.8.7` (see [DATABASE.md](DATABASE.md)) |

## A bug the live check found

The progress page (`templates/progress.html`) used `#lists.indexOf(...)`, which does not exist in Thymeleaf, so every trial page returned HTTP 500 while the trial was running. The integration tests only opened the page after the trial had finished. Fixed by calling `order.indexOf(r.status)` on the list itself, and `TrialLifecycleIT.progressPageRendersWhileTrialIsRunning` was added; it failed before the fix and passes after.

## Requirements for running the tests
Docker must be running (Testcontainers and `DockerContainerRunnerIT`). The Docker IT uses the `maven:3.9-eclipse-temurin-17` image and pulls it on first use. The e2e test also needs internet access for Maven inside the container. On JDK 21+ Mockito is loaded as a `-javaagent` (configured in `pom.xml`).
