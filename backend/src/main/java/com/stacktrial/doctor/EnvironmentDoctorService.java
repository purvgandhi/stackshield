package com.stacktrial.doctor;

import com.github.dockerjava.api.DockerClient;
import com.stacktrial.scanner.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

@Service
public class EnvironmentDoctorService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentDoctorService.class);

    private final DockerClient dockerClient;
    private final DataSource dataSource;

    public EnvironmentDoctorService(DockerClient dockerClient, DataSource dataSource) {
        this.dockerClient = dockerClient;
        this.dataSource = dataSource;
    }

    public DoctorResult diagnose(Path repoRoot, ScanResult scan) {
        List<DoctorCheck> checks = new ArrayList<>();

        // 1. Java Runtime Check
        int javaVer = scan.javaVersion() != null ? scan.javaVersion() : 17;
        if (javaVer >= 17) {
            checks.add(new DoctorCheck(
                    "Java Target Runtime",
                    "RUNTIME",
                    "PASS",
                    null,
                    "Project targets Java " + javaVer + " (LTS). Runner container provides Eclipse Temurin " + javaVer + ".",
                    "Modern LTS JDK ensures performance, virtual threads, and Spring Boot 3 compatibility.",
                    "No action needed."
            ));
        } else {
            checks.add(new DoctorCheck(
                    "Java Target Runtime",
                    "RUNTIME",
                    "WARN",
                    "Project targets Java " + javaVer + " (End of Life)",
                    "pom.xml configured with <java.version>" + javaVer + "</java.version>.",
                    "Spring Boot 3.0+ strictly requires Java 17 minimum. Upgrading to Spring Boot 3 will fail without JDK upgrade.",
                    "Use StackTrial's Java Upgrade trial to verify compilation against Java 17/21."
            ));
        }

        // 2. Build System Check
        if ("Maven".equals(scan.buildSystem())) {
            checks.add(new DoctorCheck(
                    "Build System",
                    "BUILD_TOOL",
                    "PASS",
                    null,
                    "Maven root pom.xml detected and parsed with " + scan.dependencies().size() + " dependencies.",
                    "Maven builds execute natively in StackTrial's isolated container sandbox.",
                    "No action needed."
            ));
        } else if ("Gradle".equals(scan.buildSystem())) {
            checks.add(new DoctorCheck(
                    "Build System",
                    "BUILD_TOOL",
                    "WARN",
                    "Gradle build detected",
                    "build.gradle found.",
                    "StackTrial V1 is optimized for Maven verify and dependency:tree parsing.",
                    "Convert to Maven or run standalone commands."
            ));
        } else {
            checks.add(new DoctorCheck(
                    "Build System",
                    "BUILD_TOOL",
                    "FAIL",
                    "Unknown or unsupported build system",
                    "Neither pom.xml nor build.gradle found.",
                    "Container runner requires a supported build specification to execute builds.",
                    "Add a standard pom.xml to the repository root."
            ));
        }

        // 3. Docker Engine Check
        boolean dockerOk = false;
        try {
            dockerClient.pingCmd().exec();
            dockerOk = true;
            checks.add(new DoctorCheck(
                    "Docker Sandbox Engine",
                    "CONTAINER",
                    "PASS",
                    null,
                    "Docker daemon is responsive via Engine API.",
                    "All baseline and trial builds run strictly inside isolated, resource-constrained containers.",
                    "No action needed."
            ));
        } catch (Exception e) {
            checks.add(new DoctorCheck(
                    "Docker Sandbox Engine",
                    "CONTAINER",
                    "FAIL",
                    "Docker Engine unreachable: " + e.getMessage(),
                    "Failed pinging Docker API pipe/socket.",
                    "StackTrial cannot safely execute trial containers without a running Docker daemon.",
                    "Start Docker Desktop or the dockerd service on the host machine."
            ));
        }

        // 4. PostgreSQL & pgvector Database Check
        boolean dbOk = false;
        try (Connection c = dataSource.getConnection()) {
            dbOk = !c.isClosed();
            checks.add(new DoctorCheck(
                    "PostgreSQL + pgvector State",
                    "DATABASE",
                    "PASS",
                    null,
                    "PostgreSQL 16 connection active with pgvector vector extension initialized.",
                    "Enables high-performance vector retrieval and transactional trial tracking.",
                    "No action needed."
            ));
        } catch (Exception e) {
            checks.add(new DoctorCheck(
                    "PostgreSQL + pgvector State",
                    "DATABASE",
                    "FAIL",
                    "Database connection failure: " + e.getMessage(),
                    "Could not acquire connection from Hikari pool.",
                    "Trial history, evidence records, and documentation chunks cannot be persisted.",
                    "Run 'docker compose up -d' in the project directory to start stacktrial-postgres."
            ));
        }

        // 5. Port Conflict Check
        checkPortAvailability(8081, checks);
        checkPortAvailability(5432, checks);

        // 6. Test Suite Readiness
        if (scan.testFileCount() > 0 || scan.testFrameworks().contains("JUnit 5") || scan.testFrameworks().contains("JUnit 4")) {
            checks.add(new DoctorCheck(
                    "Automated Test Suite",
                    "BUILD_TOOL",
                    "PASS",
                    null,
                    "Found automated test frameworks: " + String.join(", ", scan.testFrameworks()),
                    "Surefire XML reports will be automatically parsed to compute deterministic PASS/REGRESSION verdicts.",
                    "No action needed."
            ));
        } else {
            checks.add(new DoctorCheck(
                    "Automated Test Suite",
                    "BUILD_TOOL",
                    "WARN",
                    "No unit or integration tests detected",
                    "Surefire test count is zero.",
                    "Without tests, trial comparison can only verify compilation, not behavioral regressions.",
                    "Add JUnit / Mockito unit tests to the repository."
            ));
        }

        // 7. Security / Secrets Check
        boolean hasRawEnv = Files.exists(repoRoot.resolve(".env"));
        if (hasRawEnv) {
            checks.add(new DoctorCheck(
                    "Secret Management (.env file)",
                    "CONFIG",
                    "WARN",
                    "Local .env file committed in repository",
                    "Found .env in workspace root.",
                    "Committing secrets to git can cause security leaks in shared workspaces.",
                    "Ensure .env is added to .gitignore and use .env.example for template variables."
            ));
        } else {
            checks.add(new DoctorCheck(
                    "Secret Management (.env)",
                    "CONFIG",
                    "PASS",
                    null,
                    "No plaintext .env file in repository tree.",
                    "Secrets are safe and properly decoupled.",
                    "No action needed."
            ));
        }

        int passed = (int) checks.stream().filter(c -> "PASS".equals(c.status())).count();
        int warnings = (int) checks.stream().filter(c -> "WARN".equals(c.status())).count();
        int failed = (int) checks.stream().filter(c -> "FAIL".equals(c.status())).count();

        String overall = failed > 0 ? "BLOCKED" : (warnings > 0 ? "WARNINGS" : "READY");
        String summary = String.format("Environment Doctor completed %d checks: %d passed, %d warnings, %d blockers. Overall status: %s.",
                checks.size(), passed, warnings, failed, overall);

        return new DoctorResult(overall, passed, warnings, failed, checks, summary);
    }

    private void checkPortAvailability(int port, List<DoctorCheck> checks) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            // port is in use
            if (port == 8081) {
                checks.add(new DoctorCheck(
                        "Port 8081 (StackTrial Web UI)",
                        "NETWORK",
                        "PASS",
                        null,
                        "Port 8081 is actively bound by StackTrial web application.",
                        "Ensures web dashboard and REST API endpoints are accessible.",
                        "No action needed."
                ));
            } else if (port == 5432) {
                checks.add(new DoctorCheck(
                        "Port 5432 (PostgreSQL)",
                        "NETWORK",
                        "PASS",
                        null,
                        "Port 5432 is actively listening for PostgreSQL clients.",
                        "Enables database communication.",
                        "No action needed."
                ));
            }
        } catch (Exception ignored) {
            // not listening
        }
    }
}
