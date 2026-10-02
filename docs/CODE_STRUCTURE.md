# Code structure

All application code is in `backend/src/main/java/com/stacktrial/`, one package per module. Files marked **P0** are the ones to know for interviews (see the learning path in [INTERVIEW_GUIDE.md](INTERVIEW_GUIDE.md)).

```text
com.stacktrial
├── StackTrialApplication      Spring Boot entry point
├── web          REST API + Thymeleaf pages                  (calls trial)
├── trial        state machine, orchestration, persistence, report   (calls everything below)
├── workspace    git, workspaces, pom detection, change appliers
├── execution    Docker (docker-java), Maven run script, artifact storage
├── evidence     parsers: build log, Surefire XML, dependency tree
├── comparison   pure-Java comparison engine and verdict rules
├── explanation  knowledge base, retrieval, prompt, Gemini client, citations
└── common       typed configuration, log redaction, shared exceptions
```

Rule of thumb for dependencies: `web → trial → {workspace, execution, evidence, comparison, explanation}`. `comparison` depends only on `evidence` records. External systems sit behind interfaces: `ContainerRunner` (Docker), `ChangeApplier` (change types), `LlmClient` / `EmbeddingClient` (Gemini), `StorageService` (files).

The prompt suggested `repository/` and `config/` packages. They were not created: repositories live next to their entity (for example `trial/TrialRepository`), and configuration classes live in the module they configure (`execution/DockerConfiguration`, `trial/TrialExecutionConfiguration`). This keeps each module self-contained.

---

## web

| File | Why it exists | What it does | Who calls it | What it calls |
|---|---|---|---|---|
| **P0** `web/api/TrialApiController` | REST entry point | `POST /api/trials` (202), `GET /api/trials`, `GET /api/trials/{id}`, `GET …/runs/{side}/log`, `GET …/runs/{side}/dependency-tree` | HTTP clients (curl, scripts) | `TrialService`, `TrialReportService` |
| `web/api/CreateTrialRequest` | Request DTO with bean validation | Validates sizes / required fields; `toChange()` builds a `ChangeRequest` | `TrialApiController` | `ChangeRequest` |
| `web/api/TrialCreatedResponse` | Response DTO | `{trialId, status, reportUrl, pageUrl}` | `TrialApiController` | |
| `web/api/TrialSummary` | List item DTO | One row of `GET /api/trials` (never a JPA entity) | `TrialApiController` | `Trial` |
| `web/api/ApiExceptionHandler` | One error format | Maps exceptions to RFC 9457 problem details: 400 / 404 | Spring MVC | |
| `web/ui/TrialPageController` | Server-rendered UI | `/` dashboard, `/trials/new` form, `POST /trials`, `/trials/{id}` progress or report | Browser | `TrialService`, `TrialReportService` |
| `web/ui/TrialForm` | Form backing bean | Holds form fields | `TrialPageController` | |

Templates: `templates/dashboard.html`, `new-trial.html`, `progress.html` (auto-refresh 3 s), `report.html`, `fragments.html`. Style: `static/css/app.css`.

## trial

| File | Why it exists | What it does | Who calls it | What it calls |
|---|---|---|---|---|
| **P0** `trial/TrialService` | Single entry for creating trials | Validates URL/ref/change, saves `QUEUED`, submits to the worker pool; rejects when the queue is full | Both controllers | `RepositoryUrlValidator`, `TrialStore`, `TrialOrchestrator` (via executor) |
| **P0** `trial/TrialOrchestrator` | The pipeline in one readable method | `execute(id)`: PREPARING → RUNNING → ANALYZING → EXPLAINING → COMPLETED; errors → FAILED; cleanup in `finally` | Worker thread | `WorkspaceService`, `PomInspector`, `ChangeService`, `GitClient`, `RunnerImageResolver`, `MavenRunExecutor`, `EvidenceCollector`, `ComparisonEngine`, `ExplanationService`, `TrialStore` |
| **P0** `trial/TrialStore` | Short transactions for state changes | `moveTo`, `fail`, `recordPreparation`, `saveRun`, `saveComparison`; each is its own transaction so the UI sees progress | `TrialOrchestrator`, `TrialService`, `TrialRecovery` | JPA repositories |
| `trial/TrialExecutionConfiguration` | Async + engine wiring | Bounded `ThreadPoolTaskExecutor` (`max-concurrent-trials`, `queue-capacity`); `ComparisonEngine` bean | Spring | |
| `trial/TrialRecovery` | Honest restart behaviour | On `ApplicationReadyEvent`, marks non-terminal trials `FAILED (Interrupted …)` | Spring | `TrialRepository`, `TrialStore` |
| `trial/TrialReportService` | Read model | Builds `TrialReport` from all tables; reads stored logs | Controllers | repositories, `StorageService`, `ExplanationService` |
| `trial/TrialReport` | Report DTO | Comparison rows, runs, findings, evidence, failed tests, explanation, diff | Controllers, templates | |
| `trial/Trial` (+ `TrialStatus`) | Entity + state machine | `queued(...)`, `moveTo`, `fail`, `recordVerdict` | `TrialStore` | |
| `trial/TrialRun`, `TestResult`, `EvidenceRecord`, `FindingRecord` | Entities for `trial_run`, `test_result`, `evidence`, `finding` | Persistence only | `TrialStore`, `TrialReportService` | |
| `trial/*Repository` | Spring Data JPA | CRUD + `findTop50ByOrderByCreatedAtDesc` etc. | Store / report service | |
| `trial/TrialFailureException` | Expected failure | Message becomes `failure_reason` | Orchestrator | |

## workspace

| File | Why it exists | What it does | Who calls it | What it calls |
|---|---|---|---|---|
| **P0** `workspace/WorkspaceService` | Isolated host-side copies | `prepare`: clone into `source/`, checkout ref, copy to `baseline/` and `trial/` (no symlinks); `cleanup` | `TrialOrchestrator` | `GitClient` |
| **P1** `workspace/GitClient` | All git access | `cloneRepository`, `checkout` (ref or `origin/ref` → SHA), `applyPatch` (`--check` first), `diffAgainstHead`; timeouts, `protocol.ext.allow=never`, no prompts | `WorkspaceService`, `GitDiffChangeApplier`, orchestrator | `git` CLI via `ProcessBuilder` |
| `workspace/PomInspector` | Repository understanding | Reads root `pom.xml` (XXE-safe): Java version, Spring Boot version | Orchestrator | |
| `workspace/RepositoryUrlValidator` | Input safety | Only `https://` (+ `file:///` when allowed); rejects option-like refs | `TrialService` | |
| `workspace/PreparedWorkspaces`, `ProjectInfo` | Records | Paths + commit; detected versions | | |
| **P1** `workspace/change/ChangeApplier` | Strategy interface | `type()`, `apply(trialWorkspace, change)` | `ChangeService` | |
| `workspace/change/ChangeService` | Strategy router | Picks the applier by `ChangeType` | Orchestrator | appliers |
| `workspace/change/SpringBootVersionChangeApplier` | Spring Boot change | Edits parent version / property | `ChangeService` | `PomEditor` |
| `workspace/change/JavaVersionChangeApplier` | Java change | Edits `java.version` or compiler properties | `ChangeService` | `PomEditor` |
| `workspace/change/DependencyVersionChangeApplier` | Dependency change | Edits a declared dependency's version or its property | `ChangeService` | `PomEditor` |
| `workspace/change/GitDiffChangeApplier` | Code change | Writes patch to a temp file, `git apply` | `ChangeService` | `GitClient` |
| `workspace/change/PomEditor` | Formatting-preserving pom edits | Block-scoped text replacement; validates versions | appliers | |
| `workspace/change/ChangeRequest`, `ChangeType` | Change model | Type + fields; `describe()` | everywhere | |

## execution

| File | Why it exists | What it does | Who calls it | What it calls |
|---|---|---|---|---|
| **P0** `execution/ContainerRunner` | Docker seam | `ContainerRunResult run(ContainerRunSpec)` | `MavenRunExecutor` | |
| **P0** `execution/DockerContainerRunner` | The only Docker code | Pull image, create container with limits, copy workspace in (tar), start, wait with timeout (kill), read logs, copy results out, force-remove | `MavenRunExecutor` | docker-java `DockerClient` |
| **P0** `execution/MavenRunExecutor` | Same run for both sides | Builds the in-container script (`mvn verify`, gather Surefire XML, `dependency:tree`), runs it, redacts and stores log/tree, returns raw artifacts | Orchestrator | `ContainerRunner`, `StorageService`, `LogRedactor` |
| `execution/DockerConfiguration` | DockerClient bean | Connects via `DOCKER_HOST` (default unix socket) with Apache HttpClient 5 | Spring | |
| `execution/RunnerImageResolver` | Image per Java version | `17 → maven:3.9-eclipse-temurin-17`, `21 → …-21` | Orchestrator | |
| `execution/StorageService` / `LocalStorageService` | Artifact storage seam | Stores logs/trees under `data/artifacts/` by key | `MavenRunExecutor`, `TrialReportService` | file system |
| `execution/ContainerRunSpec`, `ContainerRunResult`, `MavenRunResult` | Records | Input/output of a run | | |

## evidence

| File | Why it exists | What it does | Who calls it | What it calls |
|---|---|---|---|---|
| **P0** `evidence/EvidenceCollector` | One place that turns files into facts | Outcome from exit code/timeout; runs the three parsers; builds `RunEvidence` | Orchestrator | the parsers |
| **P1** `evidence/SurefireReportParser` | Test results | Parses `TEST-*.xml` (DTDs/external entities disabled) → `TestCaseResult` | `EvidenceCollector` | JDK DOM |
| **P1** `evidence/MavenBuildLogParser` | Build facts | Failing plugin goal → `BuildPhase`; javac errors with symbol/location details; failure summary | `EvidenceCollector` | |
| `evidence/DependencyTreeParser` | Dependency facts | Parses `dependency:tree` text → `Dependency` list | `EvidenceCollector` | |
| `evidence/RunEvidence` + records/enums | Data model | `BuildOutcome`, `BuildPhase`, `TestOutcome`, `TestSummary`, `CompileError`, `Dependency`, `RawRunArtifacts` | | |

## comparison (pure Java)

| File | Why it exists | What it does | Who calls it | What it calls |
|---|---|---|---|---|
| **P0** `comparison/ComparisonEngine` | Deterministic verdict | Records evidence, derives findings, applies `VERDICT_RULES` (first match wins) | Orchestrator | `EvidenceLedger`, `ComparisonContext` |
| `comparison/VerdictRule` | One row of the decision table | name, predicate, verdict, reason | `ComparisonEngine` | |
| `comparison/ComparisonContext` | Helpers for rules | `anyTimeout`, `baselineFailed`, `has(FindingType)`, `count(...)` | rules | |
| `comparison/EvidenceLedger` | Stable ids | Hands out `EVIDENCE-001`, `002`, … in recording order | `ComparisonEngine` | |
| `comparison/ComparisonResult`, `Finding`, `EvidenceItem` | Output records | Verdict + reason, findings, evidence | | |
| `comparison/Verdict`, `FindingType`, `Severity`, `EvidenceKind`, `Side` | Enums | | | |

## explanation

| File | Why it exists | What it does | Who calls it | What it calls |
|---|---|---|---|---|
| **P0** `explanation/ExplanationService` | AI step that cannot touch the verdict | PASS → NOT_NEEDED; retrieve; no key → UNAVAILABLE; else prompt Gemini, validate citations; never throws | Orchestrator | `KnowledgeRetriever`, `LlmClient`, `PromptBuilder`, `CitationValidator`, `ExplanationRepository` |
| **P0** `explanation/KnowledgeRetriever` | Retrieval | Vector search (if embedded + key) else full-text with stop words, camelCase split, relative-rank cut | `ExplanationService` | `KnowledgeRepository`, `EmbeddingClient` |
| **P1** `explanation/PromptBuilder` | Grounded prompt | System rules (verdict final, cite ids, no code), findings, evidence, knowledge; also the retrieval query | `ExplanationService` | |
| **P1** `explanation/GeminiClient` | Gemini REST | `generateContent` (temperature 0.1), `embedContent` (768 dims); key in `x-goog-api-key` header | `ExplanationService`, `KnowledgeRetriever`, `KnowledgeIngestionService` | Spring `RestClient` |
| `explanation/LlmClient`, `EmbeddingClient` | AI seams | Interfaces; a stub replaces them in tests | | |
| `explanation/ExplanationConfiguration` | Conditional bean | Creates `GeminiClient` only when `GEMINI_API_KEY` is set | Spring | |
| `explanation/CitationValidator` | Hallucination check | Regex `EVIDENCE-\d{3}` / `KB-…-nn`; splits into known evidence, known knowledge, unknown | `ExplanationService` | |
| `explanation/KnowledgeLoader` | Corpus | Splits `knowledge/*.md` on `## ` into chunks with ids | `KnowledgeIngestionService` | |
| `explanation/KnowledgeIngestionService` | Startup ingestion | Upserts chunks (hash-based), embeds them if Gemini is configured | Spring (`ApplicationReadyEvent`) | `KnowledgeRepository`, `EmbeddingClient` |
| `explanation/KnowledgeRepository` | SQL for pgvector / tsvector | `searchByVector` (`<=>`), `searchByText` (`ts_rank`), upsert | retriever, ingestion | `JdbcTemplate` |
| `explanation/Explanation` (+ repository, status enum) | Entity for `explanation` | | | |

## common

| File | Why it exists | What it does |
|---|---|---|
| **P1** `common/StackTrialProperties` | Typed configuration | Record tree bound from `stacktrial.*` (runner limits, images, timeout, Gemini, RAG) |
| `common/LogRedactor` | Secret masking | Masks tokens, keys, `password=` pairs, URL credentials before storing logs or prompting Gemini |
| `common/NotFoundException` | 404 | |

## Tests (`backend/src/test/java/com/stacktrial/`)

See [TESTING.md](TESTING.md) for what each test proves.
