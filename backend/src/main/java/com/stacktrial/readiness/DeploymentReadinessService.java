package com.stacktrial.readiness;

import com.stacktrial.scanner.ScanResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class DeploymentReadinessService {

    public ReadinessResult evaluate(Path repoPath, ScanResult scanResult) {
        List<ReadinessCheck> checks = new ArrayList<>();

        // 1. Dockerfile & Containerization
        Path dockerfile = repoPath.resolve("Dockerfile");
        Path dockerCompose = repoPath.resolve("docker-compose.yml");
        if (!Files.exists(dockerCompose)) {
            dockerCompose = repoPath.resolve("docker-compose.yaml");
        }

        if (Files.exists(dockerfile)) {
            try {
                String content = Files.readString(dockerfile);
                boolean hasMultiStage = content.contains("FROM ") && content.indexOf("FROM ") != content.lastIndexOf("FROM ");
                boolean hasNonRootUser = content.contains("USER ") && !content.contains("USER root");

                if (hasMultiStage && hasNonRootUser) {
                    checks.add(new ReadinessCheck(
                            "Containerization",
                            "CONTAINERIZATION",
                            "PASS",
                            "Dockerfile detected with multi-stage build and non-root runner user.",
                            "Production containerization best practices followed."
                    ));
                } else if (hasMultiStage) {
                    checks.add(new ReadinessCheck(
                            "Containerization Non-Root User",
                            "CONTAINERIZATION",
                            "WARN",
                            "Dockerfile has multi-stage build, but does not explicitly specify a non-root USER.",
                            "Add `USER 10001:10001` before ENTRYPOINT to run as non-root."
                    ));
                } else {
                    checks.add(new ReadinessCheck(
                            "Containerization Multi-Stage",
                            "CONTAINERIZATION",
                            "WARN",
                            "Dockerfile exists but does not use multi-stage builds, leading to larger images.",
                            "Use a multi-stage Dockerfile separating the build stage from the runtime stage."
                    ));
                }
            } catch (IOException e) {
                checks.add(new ReadinessCheck("Dockerfile Read", "CONTAINERIZATION", "WARN", "Could not inspect Dockerfile", "Verify Dockerfile permissions."));
            }
        } else {
            checks.add(new ReadinessCheck(
                    "Dockerfile Missing",
                    "CONTAINERIZATION",
                    "FAIL",
                    "No Dockerfile found in root repository directory.",
                    "Add a standard Dockerfile to containerize the application for cloud deployment."
            ));
        }

        // 2. Health Checks / Actuator
        boolean hasActuator = scanResult != null && scanResult.dependencies().stream().anyMatch(d -> d.artifactId().contains("actuator"));
        if (hasActuator) {
            checks.add(new ReadinessCheck(
                    "Health & Readiness Probes",
                    "HEALTH_CHECKS",
                    "PASS",
                    "Spring Boot Actuator detected for Kubernetes liveness/readiness probes.",
                    "Configure `management.endpoint.health.probes.enabled=true` in production."
            ));
        } else {
            checks.add(new ReadinessCheck(
                    "Health & Readiness Probes",
                    "HEALTH_CHECKS",
                    "WARN",
                    "No Actuator or explicit health check dependency detected.",
                    "Add `spring-boot-starter-actuator` to expose `/actuator/health`."
            ));
        }

        // 3. Configuration Externalization
        boolean hasEnvProps = scanResult != null && scanResult.configurationProperties().values().stream().anyMatch(v -> v.contains("${"));
        if (hasEnvProps) {
            checks.add(new ReadinessCheck(
                    "Configuration Externalization",
                    "CONFIGURATION",
                    "PASS",
                    "Environment variable interpolation `${...}` detected in application configuration.",
                    "Configurations are prepared for 12-Factor cloud deployment."
            ));
        } else {
            checks.add(new ReadinessCheck(
                    "Configuration Externalization",
                    "CONFIGURATION",
                    "WARN",
                    "Configuration files appear to contain static values without environment overrides.",
                    "Externalize database passwords, URLs, and secrets using environment variables."
            ));
        }

        // 4. Logging & Observability
        boolean hasCustomLog = Files.exists(repoPath.resolve("src/main/resources/logback-spring.xml"))
                || Files.exists(repoPath.resolve("src/main/resources/logback.xml"))
                || (scanResult != null && scanResult.configurationProperties().keySet().stream().anyMatch(k -> k.startsWith("logging.")));
        if (hasCustomLog) {
            checks.add(new ReadinessCheck(
                    "Structured Logging",
                    "OBSERVABILITY",
                    "PASS",
                    "Logging configuration / properties detected.",
                    "Ensure JSON structured log formatting is enabled in production."
            ));
        } else {
            checks.add(new ReadinessCheck(
                    "Structured Logging",
                    "OBSERVABILITY",
                    "WARN",
                    "Default console logging used without explicit logback or JSON logger config.",
                    "Add logback-spring.xml with structured JSON log formatting for log aggregators."
            ));
        }

        // 5. Graceful Shutdown
        boolean hasGracefulShutdown = scanResult != null && "graceful".equalsIgnoreCase(scanResult.configurationProperties().get("server.shutdown"));
        if (hasGracefulShutdown) {
            checks.add(new ReadinessCheck(
                    "Graceful Shutdown",
                    "GRACEFUL_SHUTDOWN",
                    "PASS",
                    "server.shutdown=graceful configured to drain in-flight requests during rolling updates.",
                    "Graceful shutdown enabled."
            ));
        } else {
            checks.add(new ReadinessCheck(
                    "Graceful Shutdown",
                    "GRACEFUL_SHUTDOWN",
                    "WARN",
                    "server.shutdown is not configured for graceful shutdown.",
                    "Set `server.shutdown: graceful` to prevent dropped requests during container termination."
            ));
        }

        // 6. Build Wrapper (mvnw / gradlew)
        boolean hasWrapper = Files.exists(repoPath.resolve("mvnw")) || Files.exists(repoPath.resolve("gradlew"));
        if (hasWrapper) {
            checks.add(new ReadinessCheck(
                    "Build Wrapper Script",
                    "BUILD_CI",
                    "PASS",
                    "Repository includes wrapper script (mvnw/gradlew) for deterministic CI builds.",
                    "CI/CD runners can execute builds without pre-installed host toolchains."
            ));
        } else {
            checks.add(new ReadinessCheck(
                    "Build Wrapper Script",
                    "BUILD_CI",
                    "WARN",
                    "No wrapper script found in repository root.",
                    "Run `mvn wrapper:wrapper` or `gradle wrapper` to lock build tool versions."
            ));
        }

        // 7. Automated Test Suite
        int testCount = scanResult != null ? scanResult.testFileCount() : 0;
        if (testCount > 0) {
            checks.add(new ReadinessCheck(
                    "Automated Test Coverage",
                    "BUILD_CI",
                    "PASS",
                    testCount + " test source files detected in repository.",
                    "Ensure CI pipelines enforce test execution before merging."
            ));
        } else {
            checks.add(new ReadinessCheck(
                    "Automated Test Coverage",
                    "BUILD_CI",
                    "FAIL",
                    "No automated test suites detected.",
                    "Add JUnit/Surefire unit and integration tests."
            ));
        }

        int passed = (int) checks.stream().filter(c -> "PASS".equals(c.status())).count();
        int warned = (int) checks.stream().filter(c -> "WARN".equals(c.status())).count();
        int failed = (int) checks.stream().filter(c -> "FAIL".equals(c.status())).count();

        int score = (int) Math.round(((double) passed + (warned * 0.5)) / checks.size() * 100.0);
        String readinessStatus;
        if (failed == 0 && score >= 80) {
            readinessStatus = "PRODUCTION_READY";
        } else if (failed == 0) {
            readinessStatus = "ACTION_REQUIRED";
        } else {
            readinessStatus = "NOT_READY";
        }

        String summary = String.format("Readiness Score: %d%% (%s). %d passed, %d warnings, %d failed checks.",
                score, readinessStatus, passed, warned, failed);

        return new ReadinessResult(score, readinessStatus, passed, warned, failed, checks, summary);
    }
}
