# StackTrial Platform Feature Matrix & Status

**Application URL:** `http://localhost:8081`  
**Runtime:** Java 21 / Spring Boot 3.5.16 / PostgreSQL 16 + pgvector / Docker Engine Sandbox  

---

## Core Flow: `IMPORT → UNDERSTAND → RUN → DIAGNOSE → CHANGE → BASELINE → TRIAL → COMPARE → EXPLAIN → REPORT`

| Capability | Status | Implementation Details |
|---|---|---|
| 🔥 **GitHub Repository Import** | ✅ VERIFIED | Clones public HTTPS or local `file:///` fixtures via Git, validates URLs, initializes isolated workspace cache under `data/repositories/{id}`. |
| 🔥 **Repository Scanner** | ✅ VERIFIED | AST & filesystem scanner detects Java, Spring Boot, Maven/Gradle, databases, frameworks, lines of code, language breakdown, and health observations. |
| 🔥 **Project Overview** | ✅ VERIFIED | Tech stack gauges, dependency lists, key configuration properties, language distribution. |
| 🔥 **Architecture Extraction** | ✅ VERIFIED | AST analysis extracts 3-tier layering (Controller → Service → Repository → Entity), public REST endpoints (`@GetMapping`, `@PostMapping`, etc.), dependency targets, and external integrations. |
| 🔥 **Environment Doctor** | ✅ VERIFIED | Diagnostic matrix checks Java runtime, build tool compatibility, Docker daemon connection, PostgreSQL database status, port collisions, and test suite readiness with actionable fixes. |
| 🔥 **Docker Sandbox Runner** | ✅ VERIFIED | Runs arbitrary project commands (`mvn test`, `mvn compile`, etc.) inside disposable, resource-limited Docker containers with real-time log capture and status codes. |
| 🔥 **Security & Secret Scanner** | ✅ VERIFIED | Detects exposed AWS keys, private RSA keys, database cleartext passwords, vulnerable libraries (Log4j CVE, Spring4Shell, outdated Jackson), insecure Docker configs (`/var/run/docker.sock`, privileged mode), and assigns security grades (A-F). |
| 🔥 **PR Impact & Diff Analyzer** | ✅ VERIFIED | Calculates change blast radius, maps diffs to affected architecture nodes, identifies breaking changes, and recommends regression test suites. |
| 🔥 **Deployment Readiness** | ✅ VERIFIED | Audits Dockerfile multi-stage builds, non-root users, Spring Actuator health probes, config externalization, structured logging, and graceful shutdown. |
| 🔥 **Repository AI Q&A** | ✅ VERIFIED | Chunks source code, stores vector embeddings in PostgreSQL pgvector + tsvector full-text index, performs hybrid retrieval, and synthesizes grounded answers with exact file citations. |
| 🔥 **Technical Interview Prep** | ✅ VERIFIED | Generates repository-grounded interview deep-dives (Architecture, Concurrency, JPA/Transactions, Error Handling, Scalability) with talking points and file references. |
| 🔥 **Baseline + Trial Execution** | ✅ VERIFIED | Runs baseline and trial builds in identical isolated Docker containers with Maven Surefire test evidence collection. |
| 🔥 **Deterministic Verdict Engine** | ✅ VERIFIED | Pure Java rules compute `PASS`, `REGRESSION`, `IMPROVEMENT`, `BOTH_FAILED`, or `INCONCLUSIVE` without AI hallucinations. |
| 🔥 **Evidence-Grounded AI Explanation** | ✅ VERIFIED | Retrieves relevant migration documentation from PostgreSQL pgvector RAG, citing exact evidence IDs and knowledge base articles. |
| 🔥 **Engineering Report & Modern UI** | ✅ VERIFIED | Linear/Vercel-inspired dark developer UI with full responsive sidebar navigation, metric cards, diff viewers, and terminal log consoles. |
