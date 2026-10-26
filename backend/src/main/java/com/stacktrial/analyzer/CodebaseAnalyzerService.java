package com.stacktrial.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, rule-based static codebase analyzer.
 * Inspects repository files safely without executing arbitrary scripts.
 */
@Service
public class CodebaseAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(CodebaseAnalyzerService.class);

    // Security Patterns
    private static final Pattern AWS_KEY = Pattern.compile("(?i)(?:aws_access_key_id|aws_secret_access_key|aws_key)\\s*[:=]\\s*['\"]?([A-Za-z0-9/+=]{20,40})['\"]?");
    private static final Pattern GENERIC_API_KEY = Pattern.compile("(?i)(?:api_key|apikey|secret_key|client_secret|auth_token|access_token|private_key)\\s*[:=]\\s*['\"]([A-Za-z0-9_\\-]{16,})['\"]");
    private static final Pattern RSA_PRIVATE_KEY = Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern HARDCODED_PASSWORD = Pattern.compile("(?i)(?:password|passwd|db_password|spring\\.datasource\\.password)\\s*[:=]\\s*['\"]([^'\"\\r\\n\\${}]{3,})['\"]");
    private static final Pattern JWT_SECRET = Pattern.compile("(?i)(?:jwt\\.secret|jwt_secret|token\\.secret)\\s*[:=]\\s*['\"]([^'\"\\r\\n\\${}]{6,})['\"]");
    private static final Pattern SQL_STRING_CONCAT = Pattern.compile("(?i)(?:createStatement\\(\\)|executeQuery|executeUpdate|createQuery)\\s*\\(.*\\+.*\\)");
    private static final Pattern RUNTIME_EXEC = Pattern.compile("(?i)Runtime\\.getRuntime\\(\\)\\.exec\\(");
    private static final Pattern PROCESS_BUILDER = Pattern.compile("(?i)new\\s+ProcessBuilder\\(");

    // Docker Insecurity Patterns
    private static final Pattern DOCKER_ROOT = Pattern.compile("(?i)^\\s*USER\\s+(root|0)\\s*$");
    private static final Pattern DOCKER_SOCK = Pattern.compile("(?i)/var/run/docker\\.sock");
    private static final Pattern DOCKER_PRIVILEGED = Pattern.compile("(?i)privileged\\s*:\\s*true");

    // Dependency Vulnerabilities & EOL Patterns
    private static final Pattern LOG4J_VULN = Pattern.compile("<artifactId>log4j-core</artifactId>\\s*<version>2\\.(?:[0-9]|1[0-4]|15\\.0)</version>");
    private static final Pattern SPRING_VULN_V4S = Pattern.compile("<artifactId>spring-beans</artifactId>\\s*<version>5\\.(?:[0-2]\\.[0-9]+|3\\.(?:[0-9]|1[0-7]))</version>");
    private static final Pattern OLD_JACKSON = Pattern.compile("<artifactId>jackson-databind</artifactId>\\s*<version>2\\.(?:[0-8]\\.[0-9]+|9\\.(?:[0-9]|10\\.[0-7]))</version>");
    private static final Pattern JUNIT4_DEP = Pattern.compile("<groupId>junit</groupId>\\s*<artifactId>junit</artifactId>");
    private static final Pattern SPRING_BOOT_2 = Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>2\\.[0-9]+\\.[0-9]+</version>");
    private static final Pattern JAVA_VERSION_TAG = Pattern.compile("<(?:java\\.version|release)>(\\d+)</(?:java\\.version|release)>");

    // Code Quality Patterns
    private static final Pattern SYS_OUT = Pattern.compile("System\\.(?:out|err)\\.println\\(");
    private static final Pattern PRINT_STACK_TRACE = Pattern.compile("\\.printStackTrace\\(\\)");
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*(?:/\\*.*?\\*/|//[^\n]*|)\\s*\\}");
    private static final Pattern LEGACY_COLLECTIONS = Pattern.compile("new\\s+(?:Vector|Hashtable)(?:<[^>]*>)?\\(");
    private static final Pattern THREAD_SLEEP = Pattern.compile("Thread\\.sleep\\(");

    // Configuration Patterns
    private static final Pattern ACTUATOR_WILDCARD = Pattern.compile("management\\.endpoints\\.web\\.exposure\\.include\\s*[:=]\\s*['\"]?\\*['\"]?");
    private static final Pattern STACKTRACE_ALWAYS = Pattern.compile("server\\.error\\.include-stacktrace\\s*[:=]\\s*['\"]?always['\"]?");
    private static final Pattern INSECURE_HTTP = Pattern.compile("http://(?!localhost|127\\.0\\.0\\.1|schema\\.org|www\\.w3\\.org|maven\\.apache\\.org)[a-zA-Z0-9.\\-]+(:[0-9]+)?/[^'\"\\s]+");

    public CodebaseAnalysisReport analyze(UUID repoId, String repoUrl, String repoName, String defaultRef, Path repoRoot) {
        List<CodebaseFinding> findings = new ArrayList<>();
        Map<String, Integer> fileCountsByExt = new HashMap<>();
        int[] totalFiles = {0};
        int[] javaFiles = {0};
        long[] linesOfCode = {0};

        boolean[] hasDockerfile = {false};
        boolean[] dockerHasUser = {false};
        boolean[] hasCompose = {false};
        boolean[] hasPomXml = {false};
        boolean[] hasGradle = {false};
        boolean[] hasReadme = {false};
        boolean[] hasGitignore = {false};
        boolean[] hasMvnw = {false};
        boolean[] hasTests = {false};
        boolean[] hasGithubWorkflows = {false};

        String[] detectedJavaVersion = {"Unknown"};
        String[] detectedSpringBoot = {"None"};

        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("build") || name.equals("node_modules") || name.equals(".idea")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String rel = repoRoot.relativize(dir).toString().replace('\\', '/');
                    if (rel.equals(".github/workflows")) {
                        hasGithubWorkflows[0] = true;
                    }
                    if (rel.contains("src/test") || rel.equals("test") || rel.equals("tests")) {
                        hasTests[0] = true;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    totalFiles[0]++;
                    String name = file.getFileName().toString();
                    String rel = repoRoot.relativize(file).toString().replace('\\', '/');
                    String ext = getFileExtension(name);
                    fileCountsByExt.merge(ext, 1, Integer::sum);

                    if (name.equalsIgnoreCase("Dockerfile") || name.endsWith(".dockerfile")) {
                        hasDockerfile[0] = true;
                    } else if (name.equalsIgnoreCase("docker-compose.yml") || name.equalsIgnoreCase("docker-compose.yaml") || name.equalsIgnoreCase("compose.yml")) {
                        hasCompose[0] = true;
                    } else if (name.equals("pom.xml")) {
                        hasPomXml[0] = true;
                    } else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                        hasGradle[0] = true;
                    } else if (name.equalsIgnoreCase("README.md") || name.equalsIgnoreCase("README")) {
                        hasReadme[0] = true;
                    } else if (name.equals(".gitignore")) {
                        hasGitignore[0] = true;
                    } else if (name.equals("mvnw") || name.equals("mvnw.cmd")) {
                        hasMvnw[0] = true;
                    }

                    if (name.endsWith(".java")) {
                        javaFiles[0]++;
                        linesOfCode[0] += countLinesSafe(file);
                        boolean isTest = rel.contains("/test/") || name.endsWith("Test.java") || name.endsWith("Tests.java");
                        if (isTest) {
                            hasTests[0] = true;
                        }
                        inspectJavaFile(file, rel, isTest, findings);
                    } else if (name.equals("pom.xml")) {
                        linesOfCode[0] += countLinesSafe(file);
                        inspectPomFile(file, rel, detectedJavaVersion, detectedSpringBoot, findings);
                    } else if (name.equalsIgnoreCase("Dockerfile") || name.endsWith(".dockerfile")) {
                        linesOfCode[0] += countLinesSafe(file);
                        inspectDockerfile(file, rel, dockerHasUser, findings);
                    } else if (name.equalsIgnoreCase("docker-compose.yml") || name.equalsIgnoreCase("docker-compose.yaml")) {
                        inspectComposeFile(file, rel, findings);
                    } else if (rel.endsWith(".properties") || rel.endsWith(".yml") || rel.endsWith(".yaml")) {
                        linesOfCode[0] += countLinesSafe(file);
                        inspectConfigFile(file, rel, findings);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error walking repository {}: {}", repoRoot, e.getMessage());
        }

        // Improvement Suggestions checks
        if (!hasDockerfile[0]) {
            findings.add(new CodebaseFinding(
                    UUID.randomUUID().toString(),
                    "Improvement Suggestions",
                    "Low",
                    "Missing Docker Containerization",
                    "Dockerfile",
                    "Without a Dockerfile, builds and deployments are dependent on local machine configuration and may vary across environments.",
                    "Add a multi-stage Dockerfile using Eclipse Temurin or OpenJDK to ensure reproducible, containerized execution."
            ));
        } else if (!dockerHasUser[0]) {
            findings.add(new CodebaseFinding(
                    UUID.randomUUID().toString(),
                    "Security Risks",
                    "Medium",
                    "Docker Container Runs as Root",
                    "Dockerfile",
                    "Containers running as root have elevated privileges on the host kernel if container breakout vulnerabilities occur.",
                    "Define a dedicated non-root user in the Dockerfile: `RUN addgroup -S app && adduser -S app -G app && USER app`."
            ));
        }

        if (!hasGitignore[0]) {
            findings.add(new CodebaseFinding(
                    UUID.randomUUID().toString(),
                    "Configuration",
                    "Low",
                    "Missing .gitignore File",
                    ".gitignore",
                    "Build artifacts (e.g. target/), IDE workspace files (.idea/, .vscode/), and local secrets could accidentally be committed.",
                    "Add a standard Java/Maven .gitignore file to exclude target/, .idea/, *.class, and .env files."
            ));
        }

        if (!hasReadme[0]) {
            findings.add(new CodebaseFinding(
                    UUID.randomUUID().toString(),
                    "Configuration",
                    "Low",
                    "Missing README.md Documentation",
                    "README.md",
                    "Developers joining the project lack setup instructions, architecture overviews, and test commands.",
                    "Create a README.md detailing project prerequisites (Java version, Maven), build instructions, and endpoints."
            ));
        }

        if (!hasTests[0]) {
            findings.add(new CodebaseFinding(
                    UUID.randomUUID().toString(),
                    "Improvement Suggestions",
                    "High",
                    "Missing Automated Unit & Integration Tests",
                    "src/test/java",
                    "Without automated tests, code regressions and broken endpoints can easily reach production undetected.",
                    "Create unit tests using JUnit 5 and Mockito under `src/test/java` to verify core business logic."
            ));
        }

        if (hasPomXml[0] && !hasMvnw[0]) {
            findings.add(new CodebaseFinding(
                    UUID.randomUUID().toString(),
                    "Improvement Suggestions",
                    "Low",
                    "Missing Maven Wrapper (mvnw)",
                    "mvnw",
                    "Developers without a locally installed Maven binary of the exact version cannot reliably build the project.",
                    "Run `mvn wrapper:wrapper` to generate `mvnw` and `mvnw.cmd` scripts for consistent builds."
            ));
        }

        if (!hasGithubWorkflows[0]) {
            findings.add(new CodebaseFinding(
                    UUID.randomUUID().toString(),
                    "Improvement Suggestions",
                    "Low",
                    "Missing Continuous Integration (CI) Workflow",
                    ".github/workflows",
                    "Code changes are not automatically built and tested on pull requests, increasing risk of broken branches.",
                    "Add a GitHub Actions workflow (`.github/workflows/maven.yml`) to run `mvn clean verify` on every push."
            ));
        }

        // Category counts
        int secCount = (int) findings.stream().filter(f -> "Security Risks".equalsIgnoreCase(f.category())).count();
        int qualityCount = (int) findings.stream().filter(f -> "Code Quality".equalsIgnoreCase(f.category())).count();
        int depCount = (int) findings.stream().filter(f -> "Dependencies".equalsIgnoreCase(f.category())).count();
        int configCount = (int) findings.stream().filter(f -> "Configuration".equalsIgnoreCase(f.category())).count();
        int impCount = (int) findings.stream().filter(f -> "Improvement Suggestions".equalsIgnoreCase(f.category())).count();

        // Project Type determination
        String projectType = "Generic Project";
        if (hasPomXml[0]) {
            projectType = !"None".equals(detectedSpringBoot[0]) ? "Spring Boot Maven Application" : "Java Maven Project";
        } else if (hasGradle[0]) {
            projectType = "Gradle Project";
        } else if (javaFiles[0] > 0) {
            projectType = "Standard Java Project";
        }

        // Overall Health Grade
        long highSeverityCount = findings.stream().filter(f -> "High".equalsIgnoreCase(f.severity())).count();
        long medSeverityCount = findings.stream().filter(f -> "Medium".equalsIgnoreCase(f.severity())).count();

        String healthGrade;
        if (highSeverityCount == 0 && medSeverityCount <= 2) {
            healthGrade = "A";
        } else if (highSeverityCount == 0 && medSeverityCount <= 5) {
            healthGrade = "B";
        } else if (highSeverityCount <= 2) {
            healthGrade = "C";
        } else if (highSeverityCount <= 4) {
            healthGrade = "D";
        } else {
            healthGrade = "F";
        }

        // Top Priorities (top 3-4 actionable items)
        List<String> topPriorities = new ArrayList<>();
        findings.stream()
                .filter(f -> "High".equalsIgnoreCase(f.severity()))
                .limit(3)
                .forEach(f -> topPriorities.add(f.title() + " (" + f.affectedItem() + "): " + f.recommendation()));

        if (topPriorities.size() < 3) {
            findings.stream()
                    .filter(f -> "Medium".equalsIgnoreCase(f.severity()))
                    .limit(3 - topPriorities.size())
                    .forEach(f -> topPriorities.add(f.title() + " (" + f.affectedItem() + "): " + f.recommendation()));
        }

        if (topPriorities.isEmpty()) {
            topPriorities.add("Codebase shows healthy practices. Maintain unit test coverage and keep dependencies updated.");
        }

        String primaryLang = javaFiles[0] > 0 ? "Java" : (fileCountsByExt.isEmpty() ? "Unknown" : "Multi-Language");

        return new CodebaseAnalysisReport(
                repoId,
                repoUrl,
                repoName,
                defaultRef,
                primaryLang,
                totalFiles[0],
                javaFiles[0],
                linesOfCode[0],
                projectType,
                "COMPLETED",
                healthGrade,
                secCount,
                qualityCount,
                depCount,
                configCount,
                impCount,
                findings,
                topPriorities,
                Instant.now()
        );
    }

    private void inspectJavaFile(Path file, String relPath, boolean isTest, List<CodebaseFinding> findings) {
        try {
            List<String> lines = Files.readAllLines(file);
            String fullContent = String.join("\n", lines);

            if (!isTest) {
                // Check empty catch blocks
                Matcher catchMatcher = EMPTY_CATCH.matcher(fullContent);
                if (catchMatcher.find()) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Code Quality",
                            "Medium",
                            "Empty Catch Block (Exception Swallowing)",
                            relPath,
                            "Catching exceptions without handling or rethrowing hides unexpected application failures and makes debugging difficult.",
                            "Log the exception via SLF4J or wrap it in a domain-specific RuntimeException."
                    ));
                }

                // Check SQL string concatenation
                if (SQL_STRING_CONCAT.matcher(fullContent).find()) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Security Risks",
                            "High",
                            "Potential SQL Injection via Dynamic String Concatenation",
                            relPath,
                            "Concatenating user-controlled parameters into SQL queries allows attackers to bypass authentication or extract sensitive data.",
                            "Use parameterized queries (e.g. PreparedStatement or JPA named parameters like `:param`)."
                    ));
                }

                // Check Command execution
                if (RUNTIME_EXEC.matcher(fullContent).find() || PROCESS_BUILDER.matcher(fullContent).find()) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Security Risks",
                            "High",
                            "Host OS Process Execution Detected",
                            relPath,
                            "Direct process execution (Runtime.exec / ProcessBuilder) can lead to arbitrary command injection if arguments are unvalidated.",
                            "Use native Java libraries instead of external shell processes, or strictly sanitize inputs with allowlists."
                    ));
                }
            }

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNum = i + 1;
                String itemLoc = relPath + ":" + lineNum;

                // Secret Detection in Java
                Matcher rsaMatcher = RSA_PRIVATE_KEY.matcher(line);
                if (rsaMatcher.find()) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Security Risks",
                            "High",
                            "Hardcoded Private Cryptographic Key",
                            itemLoc,
                            "Private keys committed to source code can be used by unauthorized parties to decrypt data or forge tokens.",
                            "Remove private keys immediately, rotate the key pair, and load keys from an environment variable or KMS."
                    ));
                }

                Matcher awsMatcher = AWS_KEY.matcher(line);
                if (awsMatcher.find()) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Security Risks",
                            "High",
                            "Hardcoded AWS Credentials",
                            itemLoc,
                            "Committed cloud credentials give full access to your cloud infrastructure.",
                            "Extract AWS credentials into IAM roles or environment variables (`AWS_ACCESS_KEY_ID`)."
                    ));
                }

                if (!isTest) {
                    // System.out.println
                    if (SYS_OUT.matcher(line).find()) {
                        findings.add(new CodebaseFinding(
                                UUID.randomUUID().toString(),
                                "Code Quality",
                                "Low",
                                "Standard Output Console Logging (System.out.println)",
                                itemLoc,
                                "Standard console printing cannot be configured by log level, lacks timestamps, and hurts performance under high I/O.",
                                "Replace System.out.println with a structured SLF4J logger: `log.info(\"...\")`."
                        ));
                    }

                    // printStackTrace
                    if (PRINT_STACK_TRACE.matcher(line).find()) {
                        findings.add(new CodebaseFinding(
                                UUID.randomUUID().toString(),
                                "Code Quality",
                                "Medium",
                                "Raw Stack Trace Printed to Console (e.printStackTrace)",
                                itemLoc,
                                "Direct stack trace printing bypasses log rotation and exposes internal class details in stdout.",
                                "Use structured logging: `log.error(\"Operation failed: {}\", e.getMessage(), e)`."
                        ));
                    }

                    // Legacy collections
                    if (LEGACY_COLLECTIONS.matcher(line).find()) {
                        findings.add(new CodebaseFinding(
                                UUID.randomUUID().toString(),
                                "Code Quality",
                                "Low",
                                "Legacy Collection Usage (Vector / Hashtable)",
                                itemLoc,
                                "Vector and Hashtable synchronize every operation, adding unnecessary locking overhead in single-threaded contexts.",
                                "Replace Vector with ArrayList, and Hashtable with HashMap or ConcurrentHashMap."
                        ));
                    }

                    // Thread.sleep
                    if (THREAD_SLEEP.matcher(line).find()) {
                        findings.add(new CodebaseFinding(
                                UUID.randomUUID().toString(),
                                "Code Quality",
                                "Low",
                                "Arbitrary Thread.sleep() in Application Code",
                                itemLoc,
                                "Thread.sleep blocks thread execution, reduces throughput, and indicates fragile timing-dependent logic.",
                                "Use ScheduledExecutorService, reactive pipelines, or asynchronous event listeners."
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error inspecting Java file {}: {}", relPath, e.getMessage());
        }
    }

    private void inspectPomFile(Path file, String relPath, String[] javaVer, String[] sbVer, List<CodebaseFinding> findings) {
        try {
            String content = Files.readString(file);

            // Java version check
            Matcher javaMatcher = JAVA_VERSION_TAG.matcher(content);
            if (javaMatcher.find()) {
                String verStr = javaMatcher.group(1);
                javaVer[0] = verStr;
                try {
                    int v = Integer.parseInt(verStr);
                    if (v < 17) {
                        findings.add(new CodebaseFinding(
                                UUID.randomUUID().toString(),
                                "Dependencies",
                                "Medium",
                                "Legacy Java Baseline (Java " + v + ")",
                                relPath,
                                "Java versions older than 17 are end-of-life and miss out on critical modern performance, security, and LTS support.",
                                "Upgrade the project Java baseline to Java 17 or Java 21 LTS."
                        ));
                    }
                } catch (NumberFormatException ignored) {}
            }

            // Spring Boot 2.x EOL check
            Matcher sbMatcher = SPRING_BOOT_2.matcher(content);
            if (sbMatcher.find()) {
                sbVer[0] = "2.x";
                findings.add(new CodebaseFinding(
                        UUID.randomUUID().toString(),
                        "Dependencies",
                        "High",
                        "End-of-Life Framework: Spring Boot 2.x",
                        relPath,
                        "Spring Boot 2.x reached end of open-source support in November 2023. No further public security patches are released.",
                        "Upgrade to Spring Boot 3.x+ (requires Java 17+ and jakarta.* package namespace)."
                ));
            } else if (content.contains("spring-boot-starter")) {
                sbVer[0] = "3.x+";
            }

            // Vulnerable Log4j check
            if (LOG4J_VULN.matcher(content).find()) {
                findings.add(new CodebaseFinding(
                        UUID.randomUUID().toString(),
                        "Security Risks",
                        "High",
                        "Critical Log4j Remote Code Execution (Log4Shell / CVE-2021-44228)",
                        relPath,
                        "Vulnerable versions of Log4j 2 allow remote attackers to execute arbitrary code via JNDI lookup strings.",
                        "Upgrade org.apache.logging.log4j:log4j-core to 2.17.1 or newer."
                ));
            }

            // Spring4Shell
            if (SPRING_VULN_V4S.matcher(content).find()) {
                findings.add(new CodebaseFinding(
                        UUID.randomUUID().toString(),
                        "Security Risks",
                        "High",
                        "Spring Framework ClassLoader Manipulation (Spring4Shell / CVE-2022-22965)",
                        relPath,
                        "Allows remote code execution on Tomcat through DataBinder parameter binding.",
                        "Upgrade Spring Framework to 5.3.18+ or Spring Boot to 2.6.6+ / 3.0+."
                ));
            }

            // Old Jackson Databind
            if (OLD_JACKSON.matcher(content).find()) {
                findings.add(new CodebaseFinding(
                        UUID.randomUUID().toString(),
                        "Dependencies",
                        "Medium",
                        "Outdated Jackson Databind with Deserialization Vulnerabilities",
                        relPath,
                        "Older Jackson versions contain polymorphic deserialization gadget risks.",
                        "Upgrade com.fasterxml.jackson.core:jackson-databind to the latest stable release."
                ));
            }

            // JUnit 4 vs JUnit 5
            if (JUNIT4_DEP.matcher(content).find()) {
                findings.add(new CodebaseFinding(
                        UUID.randomUUID().toString(),
                        "Dependencies",
                        "Low",
                        "Legacy Test Framework (JUnit 4)",
                        relPath,
                        "JUnit 4 is legacy and lacks modern assertions, parameterized tests, and parallel test execution features.",
                        "Migrate test dependencies to JUnit 5 (org.junit.jupiter:junit-jupiter)."
                ));
            }

        } catch (Exception e) {
            log.debug("Error inspecting pom.xml: {}", e.getMessage());
        }
    }

    private void inspectDockerfile(Path file, String relPath, boolean[] hasUser, List<CodebaseFinding> findings) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                if (line.trim().toUpperCase().startsWith("USER") && !DOCKER_ROOT.matcher(line).find()) {
                    hasUser[0] = true;
                }
                if (DOCKER_ROOT.matcher(line).find()) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Security Risks",
                            "Medium",
                            "Explicit Root User in Dockerfile",
                            relPath,
                            "Explicitly specifying USER root prevents container isolation best practices.",
                            "Configure a non-root user in the Dockerfile."
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("Error inspecting Dockerfile: {}", e.getMessage());
        }
    }

    private void inspectComposeFile(Path file, String relPath, List<CodebaseFinding> findings) {
        try {
            String content = Files.readString(file);
            if (DOCKER_SOCK.matcher(content).find()) {
                findings.add(new CodebaseFinding(
                        UUID.randomUUID().toString(),
                        "Security Risks",
                        "High",
                        "Docker Socket Mount in Compose (/var/run/docker.sock)",
                        relPath,
                        "Mounting docker.sock gives the container root control over the host Docker daemon.",
                        "Remove the docker.sock volume mount unless this container is a dedicated monitoring agent."
                ));
            }
            if (DOCKER_PRIVILEGED.matcher(content).find()) {
                findings.add(new CodebaseFinding(
                        UUID.randomUUID().toString(),
                        "Security Risks",
                        "High",
                        "Privileged Container Execution",
                        relPath,
                        "Privileged mode disables container security boundaries, granting direct hardware access.",
                        "Remove `privileged: true` and specify only required Linux capabilities (e.g. `cap_add`)."
                ));
            }
        } catch (Exception e) {
            log.debug("Error inspecting compose file: {}", e.getMessage());
        }
    }

    private void inspectConfigFile(Path file, String relPath, List<CodebaseFinding> findings) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNum = i + 1;
                String itemLoc = relPath + ":" + lineNum;

                // Actuator wildcard
                if (ACTUATOR_WILDCARD.matcher(line).find()) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Configuration",
                            "High",
                            "Spring Actuator Wildcard Exposure (include=*)",
                            itemLoc,
                            "Exposing all actuator endpoints publishes sensitive runtime metrics, environment configs, and heap dumps.",
                            "Restrict exposed endpoints explicitly: `management.endpoints.web.exposure.include=health,info`."
                    ));
                }

                // Stacktrace always
                if (STACKTRACE_ALWAYS.matcher(line).find()) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Configuration",
                            "Medium",
                            "Full Stacktrace Included in Error Responses",
                            itemLoc,
                            "Returning raw exception traces to HTTP clients leaks internal paths, SQL queries, and framework versions.",
                            "Set `server.error.include-stacktrace=never` in production configurations."
                    ));
                }

                // Password in plain text
                Matcher pwdMatcher = HARDCODED_PASSWORD.matcher(line);
                if (pwdMatcher.find() && !line.contains("${") && !line.toLowerCase().contains("test") && !line.toLowerCase().contains("example")) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Security Risks",
                            "High",
                            "Hardcoded Password in Configuration File",
                            itemLoc,
                            "Database or service passwords committed to git repositories can be accessed by unauthorized contributors or leak in public forks.",
                            "Inject the secret via environment variable: `password: ${DB_PASSWORD:default}`."
                    ));
                }

                // JWT secret
                Matcher jwtMatcher = JWT_SECRET.matcher(line);
                if (jwtMatcher.find() && !line.contains("${")) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Security Risks",
                            "High",
                            "Hardcoded JWT Signing Secret",
                            itemLoc,
                            "If attackers obtain the JWT secret, they can forge administrative authentication tokens.",
                            "Store JWT secret keys in environment variables or an enterprise secret manager."
                    ));
                }

                // Insecure HTTP
                Matcher httpMatcher = INSECURE_HTTP.matcher(line);
                if (httpMatcher.find() && !line.contains("xmlns") && !line.contains("xsd")) {
                    findings.add(new CodebaseFinding(
                            UUID.randomUUID().toString(),
                            "Configuration",
                            "Low",
                            "Unencrypted HTTP Endpoint URL",
                            itemLoc,
                            "External communications over unencrypted HTTP are susceptible to packet sniffing and man-in-the-middle attacks.",
                            "Upgrade connection endpoints to use encrypted HTTPS protocol."
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("Error inspecting config file {}: {}", relPath, e.getMessage());
        }
    }

    private static String maskSecret(String secret) {
        if (secret == null || secret.length() < 6) {
            return "***";
        }
        return secret.substring(0, 3) + "...[REDACTED]";
    }

    private static long countLinesSafe(Path file) {
        try {
            return Files.lines(file).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String getFileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            return fileName.substring(dot + 1).toLowerCase();
        }
        return "other";
    }
}
