# Project flow: one trial from click to report

This follows one real request through the code: scenario 1, upgrading the `breaking-upgrade` fixture from Spring Boot 2.7.18 to 3.0.13. Every class named here exists in `backend/src/main/java/com/stacktrial/`.

## The flow at a glance

```text
User
 │  fills "New trial" form  (or POST /api/trials)
 ▼
UI / REST           web/ui/TrialPageController.create   web/api/TrialApiController.create
 │
 ▼
Service             trial/TrialService.create
 │                    validate URL + ref (RepositoryUrlValidator) and change fields
 │                    INSERT trial (status QUEUED)                      ← TrialStore.save
 │                    trialExecutor.execute(...)  → returns immediately (HTTP 202 / redirect)
 ▼
Worker thread       trial/TrialOrchestrator.execute(trialId)
 │
 ├─ PREPARING ──── Git clone        workspace/WorkspaceService.prepare → GitClient.cloneRepository + checkout
 │                 Workspace        copy source → baseline/ and trial/ (no symlinks, no .git copied into Docker)
 │                 Understand repo  workspace/PomInspector.inspect → Java 17, Spring Boot 2.7.18
 │                 Apply change     workspace/change/ChangeService → SpringBootVersionChangeApplier (trial/ only)
 │                 Record diff      GitClient.diffAgainstHead → stored as "applied diff"
 │
 ├─ RUNNING ────── BASELINE         execution/MavenRunExecutor.run(BASELINE)
 │                   Docker           execution/DockerContainerRunner.run → container runs `mvn verify`
 │                   Evidence         evidence/EvidenceCollector.collect → RunEvidence (8 passed)
 │                 TRIAL            execution/MavenRunExecutor.run(TRIAL)   (same method, same limits)
 │                   Docker           DockerContainerRunner.run → `mvn verify` on the changed copy
 │                   Evidence         EvidenceCollector.collect → RunEvidence (6 passed / 2 failed)
 │
 ├─ ANALYZING ──── Comparison       comparison/ComparisonEngine.compare(baseline, trial)
 │                 Verdict          first matching VERDICT_RULES row → REGRESSION
 │
 ├─ EXPLAINING ─── RAG              explanation/KnowledgeRetriever.retrieve → KB-SB30-03 (trailing slash)
 │                 Gemini           explanation/ExplanationService → LlmClient.generate (only if GEMINI_API_KEY)
 │                                  CitationValidator checks every [EVIDENCE-xxx] / [KB-…] id
 │
 └─ COMPLETED
 ▼
Report              trial/TrialReportService.report → TrialReport (JSON for /api/trials/{id}, HTML for /trials/{id})
```

## Step by step

### 1. User → UI or API
- UI: `GET /trials/new` renders `templates/new-trial.html`. Submitting posts the form to `POST /trials` (`TrialPageController.create`), which redirects to `/trials/{id}`.
- API: `POST /api/trials` with JSON (`CreateTrialRequest`) returns `202 Accepted` with the trial id (`TrialApiController.create`).

Both call the same `TrialService.create(repoUrl, baselineRef, ChangeRequest)`.

### 2. Service → trial creation
`TrialService.create`:
1. `RepositoryUrlValidator.validate`: only `https://…` or (for demos) `file:///…`; refs that cannot be git options.
2. `validateChange`: the fields required for the change type (for example `groupId` for a dependency change).
3. `TrialStore.save(Trial.queued(...))`: a row in `trial` with status `QUEUED`.
4. `trialExecutor.execute(() -> orchestrator.execute(id))`: hands the work to a bounded thread pool (`TrialExecutionConfiguration`). If the queue is full the trial is marked `FAILED` instead of blocking the request.

The HTTP request ends here. Everything below runs on a `trial-worker-N` thread.

### 3. Git clone → workspace
`WorkspaceService.prepare(trialId, repoUrl, ref)` creates `data/workspaces/{trialId}/`:

```text
source/    git clone --no-checkout, then checkout --detach <commit of ref>
baseline/  copy of source
trial/     copy of source; the change is applied here only
```

`GitClient` runs the `git` CLI through `ProcessBuilder` with timeouts and `protocol.ext.allow=never`. Git never runs repository code.

### 4. Repository understanding
`PomInspector.inspect` reads the root `pom.xml` of each side: Java version (`java.version`, else `maven.compiler.release`, `target` or `source`), Spring Boot version (parent or `spring-boot.version`). If there is no `pom.xml`, the trial fails with "unsupported project".

### 5. Apply the proposed change
`ChangeService.apply` picks the `ChangeApplier` for the type:

| Change type | Class | What it edits |
|---|---|---|
| `SPRING_BOOT_VERSION` | `SpringBootVersionChangeApplier` | parent version or `spring-boot.version` |
| `JAVA_VERSION` | `JavaVersionChangeApplier` | `java.version` / compiler properties |
| `DEPENDENCY_VERSION` | `DependencyVersionChangeApplier` | the dependency's version or its `${property}` |
| `GIT_DIFF` | `GitDiffChangeApplier` | `git apply --check` then `git apply` |

Then `git add -A && git diff --cached HEAD` records exactly what changed. An empty diff fails the trial ("The change produced no difference").

`RunnerImageResolver.imageFor(javaVersion)` picks the image per side, so a Java 17 → 21 trial runs baseline on `maven:3.9-eclipse-temurin-17` and trial on `-21`.

### 6. Baseline → Docker → evidence
`MavenRunExecutor.run(trialId, BASELINE, baselineDir, image)` builds a `ContainerRunSpec` and calls `ContainerRunner.run` (implemented by `DockerContainerRunner`). Inside the container:

```sh
mvn -B -ntp verify                   > .stacktrial/build.log
copy every target/surefire-reports/TEST-*.xml → .stacktrial/surefire-reports/
mvn dependency:tree                  → .stacktrial/dependency-tree.txt
exit with the exit code of mvn verify
```

`.stacktrial/` is copied back to the host, the build log is redacted and stored, and `EvidenceCollector.collect` parses it into `RunEvidence`: outcome, exit code, phase reached, tests, compile errors, dependencies, duration. `TrialStore.saveRun` writes `trial_run` and `test_result` rows.

### 7. Trial → Docker → evidence
Exactly the same method with `Side.TRIAL` and the trial workspace. Same limits, same user, same network, same script. See [DOCKER_FLOW.md](DOCKER_FLOW.md).

### 8. Comparison → verdict
`ComparisonEngine.compare(baseline, trial)` is pure Java. It records numbered evidence (`EVIDENCE-001` …), derives findings (`BUILD_REGRESSION`, `TEST_REGRESSION`, `DEPENDENCY_CHANGED`, …), and takes the verdict from the first matching rule of `VERDICT_RULES`. Scenario 1 matches rule 3, "trial-build-broke": `REGRESSION`. `TrialStore.saveComparison` writes `evidence`, `finding` and the verdict. See [COMPARISON_ENGINE.md](COMPARISON_ENGINE.md).

### 9. RAG → Gemini
`ExplanationService.explain`:
1. `PASS` → stores `NOT_NEEDED` and stops.
2. Builds a search query from the change, the non-informational findings and failure messages; `KnowledgeRetriever` searches `knowledge_chunk` with pgvector (if Gemini is configured) or PostgreSQL full-text search.
3. No key → stores `UNAVAILABLE` with the retrieved documentation. The deterministic report is still complete.
4. Key → `PromptBuilder` builds a grounded prompt (verdict marked final), `GeminiClient.generate` calls the API, `CitationValidator` checks every cited id.

The verdict was saved in step 8; nothing in this step can change it. See [RAG_FLOW.md](RAG_FLOW.md) and [GEMINI_FLOW.md](GEMINI_FLOW.md).

### 10. Report
`TrialReportService.report(id)` assembles a `TrialReport` record from the database (comparison table, findings, evidence, failed tests, explanation, applied diff). The same record is:
- returned as JSON by `GET /api/trials/{id}`,
- rendered by `templates/progress.html` while running (auto-refresh every 3 s) and `templates/report.html` when finished.

### Cleanup and failure
`TrialOrchestrator.execute` has a `finally` that deletes the collected results and the trial's workspaces. Expected errors (repository unavailable, unsupported project, change cannot be applied, Docker failure) end the trial as `FAILED` with a readable `failure_reason`. On startup `TrialRecovery` marks any trial left in a non-terminal state as `FAILED` ("Interrupted: StackTrial restarted …").

## Status transitions

```text
QUEUED → PREPARING → RUNNING → ANALYZING → EXPLAINING → COMPLETED
   └──────────┴──────────┴──────────┴───────────┴──────→ FAILED (with failure_reason)
```
