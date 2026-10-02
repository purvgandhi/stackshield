# CLAUDE.md: how to work on STACKTRIAL

Rules for any AI assistant (and any human) working in this repository. Read `PROJECT.md` (scope, decisions, status), `docs/ARCHITECTURE.md` and `docs/CODE_STRUCTURE.md` first.

## 1. Mode

Aashu switched to **build mode** on 2026-10-02: build first, keep the code clean, keep the architecture simple, do not overengineer, do not invent features. Do not stop between phases for approval. When a choice is ambiguous: pick the simplest reasonable option, record it in `PROJECT.md` §3, and continue. Still ask before pushing to GitHub, opening PRs, or adding technology that build mode excluded.

## 2. The rule above all others

**Evidence first. AI second.** Execution → evidence → deterministic comparison → verdict → retrieval → Gemini explanation. Gemini never decides or changes the verdict. If Gemini is unavailable, the deterministic report must still be complete.

## 3. Priority order

1. Working vertical slice → 2. Correct architecture → 3. Real Docker execution → 4. Deterministic evidence → 5. AI/RAG explanation → 6. Testing → 7. UI polish.
Never sacrifice the working core to add technology.

## 4. Stack (fixed for V1)

Java 21, Spring Boot 3.5, Maven, PostgreSQL 16 + pgvector, Flyway, docker-java, Thymeleaf, Gemini REST (via `LlmClient` / `EmbeddingClient`), JUnit 5, Mockito, Spring Boot Test, Testcontainers.
**Not in V1:** Kubernetes, microservices, Kafka, Redis, Gradle, React, other languages, cloud deployment, multi-tenancy, billing, complex auth, benchmarking, AI-generated code/fixes. Do not add libraries without a clear need.

## 5. No fake functionality

Forbidden: fake trial results, hardcoded PASS/REGRESSION, fake Docker or test output, fake AI responses, fake retrieval, placeholder reports presented as real. If something is not implemented, say **NOT IMPLEMENTED**. Test doubles are allowed only in tests and only at external boundaries (Docker in `TrialLifecycleIT`, Gemini in `ExplanationServiceIT`; `DockerContainerRunnerIT` uses real Docker); never mock the comparison result.

## 6. Architecture rules

- Packages `trial, workspace, execution, evidence, comparison, explanation, web, common` under `com.stacktrial`.
- External systems only behind interfaces: `ContainerRunner` (Docker), `ChangeApplier` (change types), `LlmClient` / `EmbeddingClient` (Gemini), `StorageService` (artifacts).
- `comparison` stays pure Java: no Spring, no I/O, no clock. Every verdict rule has a unit test.
- Baseline and trial use the same `MavenRunExecutor.run` and the same global runner config. Never add per-side settings.
- Constructor injection only; typed config in `StackTrialProperties`; DTOs/records at the web boundary, never JPA entities.
- Schema changes only through new Flyway migrations; never edit an applied one.
- Errors become trial states (`FAILED` + `failure_reason`); nothing may stay `RUNNING`.

## 7. Docker rules

Target code never runs on the host. Every runner container: CPU/memory/PIDs limits, hard timeout + kill, user `1000:1000`, `CapDrop ALL`, `no-new-privileges`, not privileged, no Docker socket, no host bind mount of the workspace (copy in/out), always removed.

## 8. Security rules

No secrets in git (`.env` is ignored, `.env.example` documents variables). Redact logs before storing them and before sending anything to Gemini. Never log or return API keys. Validate repository URLs, refs and versions before using them.

## 9. Commands

```bash
docker compose up -d                                   # PostgreSQL 16 + pgvector on 127.0.0.1:5432
cd backend
mvn clean verify                                       # 72 unit + integration tests (needs Docker)
mvn verify -Pe2e                                       # + real-Docker end-to-end test
mvn spring-boot:run                                    # app on http://127.0.0.1:8080
sh ../fixtures/create-demo-repos.sh                    # local fixture git repos for demos
sh ../scripts/run-demo-scenarios.sh                    # 4 real scenarios, saved to docs/scenario-results/
```

Behind an HTTPS proxy, start with `--spring.config.additional-location=file:config/<your-proxy>.yml` (see `backend/config/application-proxy.example.yml`); pass the same `-Dspring.config.additional-location=...` to `mvn verify -Pe2e`.

## 10. Teaching

Aashu will study the system after the MVP. Keep `docs/INTERVIEW_GUIDE.md` and `docs/CODE_STRUCTURE.md` current: when a significant decision or class changes, update them and the decisions log in `PROJECT.md`. Never document a class that does not exist.
