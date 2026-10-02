# REST API

Base URL: `http://localhost:8080` (the server binds to `127.0.0.1`; there is no authentication in V1). Controller: `web/api/TrialApiController.java`. Errors use RFC 9457 problem details (`web/api/ApiExceptionHandler.java`).

Every endpoint below was called against the running application on 2026-10-02; the raw output is in [verification/api-checks.txt](verification/api-checks.txt), and full reports for each scenario are in [scenario-results/](scenario-results/). The same endpoints are also covered by `TrialLifecycleIT` and `ExplanationServiceIT` (MockMvc).

| Method | URL | Purpose |
|---|---|---|
| `POST` | `/api/trials` | Create and queue a trial |
| `GET` | `/api/trials` | List the 50 most recent trials |
| `GET` | `/api/trials/{id}` | Status while running, full report when finished |
| `GET` | `/api/trials/{id}/runs/{side}/log` | Redacted Maven build log of `BASELINE` or `TRIAL` |
| `GET` | `/api/trials/{id}/runs/{side}/dependency-tree` | `mvn dependency:tree` output of one side |
| `GET` | `/actuator/health` | Liveness (Spring Boot Actuator) |

UI routes (Thymeleaf, `web/ui/TrialPageController.java`): `GET /` dashboard, `GET /trials/new` form, `POST /trials` form submit (redirects to the trial page), `GET /trials/{id}` progress page or report.

---

## POST /api/trials

**Purpose:** validate the request, store the trial as `QUEUED`, hand it to a worker thread and return immediately. Docker runs take from seconds to minutes, so the client polls `GET /api/trials/{id}`.

**Request** (`application/json`, `CreateTrialRequest`):

| Field | Type | Required | Notes |
|---|---|---|---|
| `repoUrl` | string | yes | `https://host/path` or, when `STACKTRIAL_ALLOW_LOCAL_REPOS=true`, `file:///path` |
| `baselineRef` | string | yes | branch, tag or commit SHA |
| `changeType` | enum | yes | `SPRING_BOOT_VERSION`, `JAVA_VERSION`, `DEPENDENCY_VERSION`, `GIT_DIFF` |
| `targetVersion` | string | for all except `GIT_DIFF` | e.g. `3.0.13`, `21`, `3.17.0` |
| `groupId`, `artifactId` | string | for `DEPENDENCY_VERSION` | the dependency must be declared in `pom.xml` |
| `diff` | string | for `GIT_DIFF` | output of `git diff`; only allowed for this type |

Examples:

```bash
# Spring Boot upgrade
curl -s -X POST localhost:8080/api/trials -H 'Content-Type: application/json' -d '{
  "repoUrl": "file:///tmp/stacktrial-fixture-repos/breaking-upgrade",
  "baselineRef": "main", "changeType": "SPRING_BOOT_VERSION", "targetVersion": "3.0.13"}'

# Dependency upgrade
curl -s -X POST localhost:8080/api/trials -H 'Content-Type: application/json' -d '{
  "repoUrl": "file:///tmp/stacktrial-fixture-repos/passing-upgrade", "baselineRef": "main",
  "changeType": "DEPENDENCY_VERSION", "groupId": "org.apache.commons", "artifactId": "commons-lang3", "targetVersion": "3.17.0"}'

# A public GitHub repository
curl -s -X POST localhost:8080/api/trials -H 'Content-Type: application/json' -d '{
  "repoUrl": "https://github.com/spring-projects/spring-petclinic", "baselineRef": "main",
  "changeType": "JAVA_VERSION", "targetVersion": "21"}'

# A git diff (jq encodes the file as a JSON string)
jq -n --rawfile d fixtures/diffs/compile-break.diff \
  '{repoUrl:"file:///tmp/stacktrial-fixture-repos/passing-upgrade", baselineRef:"main", changeType:"GIT_DIFF", diff:$d}' |
  curl -s -X POST localhost:8080/api/trials -H 'Content-Type: application/json' -d @-
```

**Response** `202 Accepted`, header `Location: /api/trials/{id}`:

```json
{"trialId":"d48aad1c-cdca-46e1-8f94-26fb5f6a87fc","status":"QUEUED",
 "reportUrl":"/api/trials/d48aad1c-cdca-46e1-8f94-26fb5f6a87fc","pageUrl":"/trials/d48aad1c-cdca-46e1-8f94-26fb5f6a87fc"}
```

**Errors** `400 Bad Request` (no trial is created):

```json
{"type":"about:blank","title":"Invalid request","status":400,
 "detail":"Repository URL must be https://host/path or file:///path","instance":"/api/trials"}
{"type":"about:blank","title":"Invalid request","status":400,
 "detail":"groupId is required for this change type","instance":"/api/trials"}
```

Problems found later (repository unreachable, ref not found, no `pom.xml`, diff does not apply, Docker failure, queue full) do not fail the request: the trial ends with `status: FAILED` and a `failureReason`.

---

## GET /api/trials

**Purpose:** the dashboard data: the 50 most recent trials, newest first.

**Response** `200`, array of `TrialSummary`:

```json
[{"id":"0e5df89a-495b-4f08-8dac-eddb400a261e",
  "repoUrl":"file:///tmp/stacktrial-fixture-repos/breaking-upgrade","baselineRef":"main",
  "changeType":"SPRING_BOOT_VERSION",
  "changeSummary":"Upgrade Spring Boot to 3.0.13 — Spring Boot 2.7.18 -> 3.0.13 (spring-boot-starter-parent)",
  "status":"COMPLETED","currentStep":"Completed","verdict":"REGRESSION","createdAt":"2026-10-02T11:14:24.600797Z"}]
```

---

## GET /api/trials/{id}

**Purpose:** progress and the complete report. Poll until `finished` is `true`.

**Response** `200`, a `TrialReport` (trial/TrialReport.java). Trimmed real output from scenario 1:

```json
{
  "id": "a5c9f459-8288-4088-a97a-79e3d84ec532",
  "repoUrl": "file:///tmp/stacktrial-fixture-repos/breaking-upgrade",
  "baselineRef": "main",
  "baselineCommit": "249b1d3393541ed95f46f1ce695ba52fec9c490d",
  "changeType": "SPRING_BOOT_VERSION",
  "changeSummary": "Upgrade Spring Boot to 3.0.13 — Spring Boot 2.7.18 -> 3.0.13 (spring-boot-starter-parent)",
  "status": "COMPLETED",
  "currentStep": "Completed",
  "finished": true,
  "verdict": "REGRESSION",
  "verdictReason": "Baseline build succeeded but the trial build failed in phase TEST; 2 test(s) that passed in baseline fail in trial.",
  "failureReason": null,
  "environment": {"baselineJava": 17, "trialJava": 17, "baselineSpringBoot": "2.7.18", "trialSpringBoot": "3.0.13"},
  "baseline": {
    "side": "BASELINE", "image": "maven:3.9-eclipse-temurin-17", "outcome": "SUCCESS", "exitCode": 0,
    "timedOut": false, "phaseReached": "VERIFY", "durationMs": 10881,
    "tests": {"total": 8, "passed": 8, "failed": 0, "errors": 0, "skipped": 0},
    "compileErrors": 0, "dependencies": 60, "failureSummary": null,
    "buildLogUrl": "/api/trials/a5c9…/runs/BASELINE/log",
    "dependencyTreeUrl": "/api/trials/a5c9…/runs/BASELINE/dependency-tree"
  },
  "trial": { "...": "same shape as baseline" },
  "comparison": [
    {"label": "SPRING BOOT", "baseline": "2.7.18", "trial": "3.0.13", "changed": true},
    {"label": "BUILD", "baseline": "PASS", "trial": "FAIL", "changed": true},
    {"label": "TESTS", "baseline": "8 passed", "trial": "6 passed / 2 failed", "changed": true}
  ],
  "findings": [
    {"type": "TEST_REGRESSION", "severity": "HIGH",
     "title": "Test regressed: com.example.orders.OrderControllerTest#listsOrdersWithTrailingSlash",
     "description": "Passed in baseline, FAILED in trial: Status expected:<200> but was:<404>",
     "baselineEvidence": ["EVIDENCE-003"], "trialEvidence": ["EVIDENCE-004"]}
  ],
  "evidence": [
    {"code": "EVIDENCE-004", "kind": "TEST_FAILURE", "side": "TRIAL",
     "title": "Trial test FAILED: com.example.orders.OrderControllerTest#listsOrdersWithTrailingSlash",
     "detail": "java.lang.AssertionError: Status expected:<200> but was:<404> …"}
  ],
  "failedTests": [
    {"side": "TRIAL", "test": "com.example.orders.OrderControllerTest#listsOrdersWithTrailingSlash",
     "outcome": "FAILED", "type": "java.lang.AssertionError", "message": "Status expected:<200> but was:<404>",
     "stackTrace": "…"}
  ],
  "explanation": {
    "status": "UNAVAILABLE", "model": null, "retrievalMethod": "FULL_TEXT", "content": null,
    "note": "GEMINI_API_KEY is not configured. The deterministic report is complete; the retrieved documentation below was selected without AI.",
    "citedEvidence": [], "citedKnowledge": [], "unknownCitations": [],
    "documentation": [
      {"id": "KB-SB30-03", "source": "Spring Boot 3.0 Migration Guide (curated summary)",
       "sourceUrl": "https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide",
       "title": "Spring MVC and WebFlux: trailing slash matching disabled", "content": "…", "cited": false}
    ]
  },
  "appliedDiff": "diff --git a/pom.xml b/pom.xml\n…-        <version>2.7.18</version>\n+        <version>3.0.13</version>…",
  "createdAt": "…", "startedAt": "…", "finishedAt": "…"
}
```

With a Gemini key, `explanation.status` is `GENERATED`, `content` holds the text, `model` names the model, and `citedEvidence` / `citedKnowledge` / `unknownCitations` hold the validated citations. While running, `verdict` is `null`, `finished` is `false` and `currentStep` says what is happening (e.g. `Running trial in Docker (maven:3.9-eclipse-temurin-17)`).

**Errors:** `404` for an unknown id:

```json
{"type":"about:blank","title":"Not found","status":404,
 "detail":"Trial 00000000-0000-0000-0000-000000000000 not found","instance":"/api/trials/00000000-0000-0000-0000-000000000000"}
```

---

## GET /api/trials/{id}/runs/{side}/log

**Purpose:** the Maven build log of one side, redacted by `LogRedactor` before it was stored. `side` is `BASELINE` or `TRIAL`.

**Response** `200 text/plain`:

```text
[INFO] Results:
[ERROR] Failures:
[ERROR]   OrderControllerTest.listsOrdersWithTrailingSlash:44 Status expected:<200> but was:<404>
…
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:… There are test failures.
```

`404` if the trial or log does not exist; `400` if `side` is not `BASELINE`/`TRIAL`.

## GET /api/trials/{id}/runs/{side}/dependency-tree

**Purpose:** the resolved dependency tree of one side (`mvn dependency:tree`).

**Response** `200 text/plain`:

```text
com.example:orders-service:jar:1.0.0
+- org.springframework.boot:spring-boot-starter-web:jar:2.7.18:compile
|  +- org.springframework.boot:spring-boot-starter:jar:2.7.18:compile
```

## GET /actuator/health

**Response** `200` `{"status":"UP"}` (includes the database check).
