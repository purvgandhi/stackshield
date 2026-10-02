# Runbook

Exact commands to run StackTrial on your machine (Linux or macOS; on Windows use WSL2). All commands start from the repository root (`stacktrial/`) unless they `cd` somewhere.

## 1. Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker Engine / Docker Desktop | running | `docker info` |
| git | any recent | `git --version` |
| curl, jq | only for the API examples and the scenario script | `jq --version` |

Docker must be reachable at the default socket. On Docker Desktop for macOS, either keep *Settings → Advanced → Allow the default Docker socket* enabled, or `export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`.

Runner containers download Maven dependencies, so they need internet access (the default `bridge` network works on a normal machine).

## 2. Configure environment variables

```bash
cp .env.example .env
# edit .env: set GEMINI_API_KEY if you have one (optional)
set -a; . ./.env; set +a          # export everything in .env into this shell
```

Spring Boot does not read `.env` by itself; the `set -a` line exports the values. Everything has a working default, so this step is optional without a Gemini key.

| Variable | Default | Purpose |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/stacktrial` | PostgreSQL |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `stacktrial` / `stacktrial` | PostgreSQL |
| `GEMINI_API_KEY` | empty | enables embeddings + AI explanation |
| `GEMINI_CHAT_MODEL` | `gemini-2.5-flash` | |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-001` | |
| `STACKTRIAL_DATA_DIR` | `./data` | workspaces, collected results, stored logs |
| `STACKTRIAL_RUN_TIMEOUT` | `15m` | hard timeout per container |
| `STACKTRIAL_RUNNER_NETWORK` | `bridge` | Docker network of runner containers |
| `STACKTRIAL_ALLOW_LOCAL_REPOS` | `true` | accept `file:///` repositories (demo fixtures) |
| `PORT` / `SERVER_ADDRESS` | `8080` / `127.0.0.1` | HTTP server |

## 3. Start PostgreSQL + pgvector

```bash
docker compose up -d
docker compose ps                 # stacktrial-postgres should be "healthy"
```

## 4. Build

```bash
cd backend
mvn clean verify                  # compiles, runs 72 tests (Docker must be running), builds the jar
# faster, without tests:
mvn clean package -DskipTests
```

The first build downloads dependencies and pulls `pgvector/pgvector:pg16`, `testcontainers/ryuk` and `maven:3.9-eclipse-temurin-17`.

Optional real end-to-end test (two real containers, about 1–3 minutes):

```bash
mvn verify -Pe2e
```

## 5. Start Spring Boot

```bash
# still in backend/
java -jar target/stacktrial-0.1.0-SNAPSHOT.jar
# or: mvn spring-boot:run
```

Expected log lines:

```text
Migrating schema "public" to version "1 - init"            (first start only)
Started StackTrialApplication in … seconds
Knowledge base loaded: 22 chunks
GEMINI_API_KEY not set: knowledge retrieval will use PostgreSQL full-text search   (or "Embedded 22 knowledge chunks")
```

Health check: `curl -s localhost:8080/actuator/health` → `{"status":"UP"}`.

## 6. Open the UI

<http://localhost:8080>

## 7. Create the demo repositories

In a second terminal, from the repository root:

```bash
sh fixtures/create-demo-repos.sh
# file:///tmp/stacktrial-fixture-repos/passing-upgrade
# file:///tmp/stacktrial-fixture-repos/breaking-upgrade
```

## 8–11. Create a trial and read the result

In the UI click **New trial** and enter:

| Field | Value |
|---|---|
| Repository URL | `file:///tmp/stacktrial-fixture-repos/breaking-upgrade` |
| Baseline ref | `main` |
| Change type | `SPRING_BOOT_VERSION` |
| Target version | `3.0.13` |

Then:

8. **Baseline runs**: the progress page shows `RUNNING · Running baseline in Docker (maven:3.9-eclipse-temurin-17)`. `docker ps` shows a container named `stacktrial-<id>-baseline-…`.
9. **Trial runs**: the step changes to `Running trial in Docker …` and the card "Baseline finished: SUCCESS 8 passed" appears.
10. **Comparison**: when finished, the page becomes the report: verdict `REGRESSION`, the baseline-vs-trial table (`BUILD PASS → FAIL`, `TESTS 8 passed → 6 passed / 2 failed`), findings with evidence codes, failed tests, the applied diff, and links to both build logs.
11. **AI explanation**: section **Why did it fail?**. Without a key it says the explanation is unavailable and lists the documentation found (`KB-SB30-03 Spring MVC and WebFlux: trailing slash matching disabled`). With a key it shows Gemini's text with `[EVIDENCE-…]` / `[KB-…]` citations.

The same through the API:

```bash
ID=$(curl -s -X POST localhost:8080/api/trials -H 'Content-Type: application/json' \
  -d '{"repoUrl":"file:///tmp/stacktrial-fixture-repos/breaking-upgrade","baselineRef":"main","changeType":"SPRING_BOOT_VERSION","targetVersion":"3.0.13"}' \
  | jq -r .trialId)
# poll until finished
until [ "$(curl -s localhost:8080/api/trials/$ID | jq -r .finished)" = true ]; do sleep 5; done
curl -s localhost:8080/api/trials/$ID | jq '{status, verdict, verdictReason, comparison: [.comparison[] | "\(.label): \(.baseline) -> \(.trial)"]}'
curl -s localhost:8080/api/trials/$ID | jq .explanation
curl -s localhost:8080/api/trials/$ID/runs/TRIAL/log | tail -30
```

## All four demo scenarios in one command

```bash
sh scripts/run-demo-scenarios.sh
```

It creates the four trials one after another, waits for each, and saves `request.json`, `report.json`, both build logs and both dependency trees to `docs/scenario-results/<scenario>/`.

| # | Repository | Change | Result observed on 2026-10-02 |
|---|---|---|---|
| 1 | `breaking-upgrade` | `SPRING_BOOT_VERSION` → `3.0.13` | `REGRESSION`: build PASS → FAIL (TEST), 8 passed → 6 passed / 2 failed (`Status expected:<200> but was:<404>` on trailing-slash URLs) |
| 2 | `passing-upgrade` | `DEPENDENCY_VERSION` `org.apache.commons:commons-lang3` → `3.17.0` | `PASS`: 5 → 5 passed, one `DEPENDENCY_CHANGED` finding |
| 3 | `passing-upgrade` | `JAVA_VERSION` → `21` | `PASS`: baseline on `temurin-17`, trial on `temurin-21`, 5 → 5 passed |
| 4 | `passing-upgrade` | `GIT_DIFF` from `fixtures/diffs/compile-break.diff` | `REGRESSION`: phase VERIFY → COMPILE, `cannot find symbol \| symbol: method truncateMiddle(java.lang.String,int) \| location: class org.apache.commons.lang3.StringUtils` |

A public GitHub repository works the same way:

```bash
curl -s -X POST localhost:8080/api/trials -H 'Content-Type: application/json' \
  -d '{"repoUrl":"https://github.com/spring-projects/spring-petclinic","baselineRef":"main","changeType":"JAVA_VERSION","targetVersion":"21"}'
```
(observed: `PASS`, 77 passed / 2 skipped on both sides, Java 17 → 21 images.)

Timing: the first run of each project downloads its Maven dependencies into the `stacktrial-m2-cache` volume and can take several minutes. Later runs of the fixtures take about 10 seconds per side.

## Stopping and resetting

```bash
# stop the app: Ctrl+C
docker compose down               # stop PostgreSQL, keep data
docker compose down -v            # stop PostgreSQL and delete all trials
docker volume rm stacktrial-m2-cache   # clear the Maven cache used by runner containers
rm -rf backend/data               # stored logs (relative to where the app was started)
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| App fails at startup: `Connection to localhost:5432 refused` | `docker compose up -d`, wait for healthy |
| Trial `FAILED`: `Docker execution failed: … docker.sock` | Docker not running or socket not at the default path; see `DOCKER_HOST` in step 1 |
| Trial `FAILED`: `Could not pull runner image` | No access to Docker Hub; `docker pull maven:3.9-eclipse-temurin-17` manually (and `-21`) |
| Trial stays in `RUNNING` for minutes the first time | Maven is downloading dependencies inside the container; normal on first run |
| Both builds fail in `DEPENDENCY_RESOLUTION` (`BOTH_FAILED`) | Runner containers have no internet. Behind a corporate proxy, start with `--spring.config.additional-location=file:config/application-proxy.local.yml` (copy from `backend/config/application-proxy.example.yml`) |
| `Repository URL must be https://host/path or file:///path` | ssh / git:// URLs are not accepted by design |
| Trial `FAILED`: `No pom.xml at the repository root` | V1 supports Maven projects with a root `pom.xml` only |
| Trial `FAILED`: `No runner image configured for Java 11` | Images exist for 17 and 21; add one under `stacktrial.runner.images` |
| `mvn verify` fails with Testcontainers `Could not find a valid Docker environment` | Docker is not running for the tests either |
| Trial `FAILED`: `Interrupted: StackTrial restarted while this trial was RUNNING` | The app was stopped mid-trial; create the trial again |
| Explanation `FAILED` with an HTTP error | Invalid Gemini key or quota; the verdict and report are unaffected |
