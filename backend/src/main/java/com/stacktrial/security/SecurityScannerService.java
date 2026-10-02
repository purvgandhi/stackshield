package com.stacktrial.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SecurityScannerService {

    private static final Logger log = LoggerFactory.getLogger(SecurityScannerService.class);

    // Secret Detection Patterns
    private static final Pattern AWS_KEY = Pattern.compile("(?i)(?:aws_access_key_id|aws_secret_access_key|aws_key)\\s*[:=]\\s*['\"]?([A-Za-z0-9/+=]{20,40})['\"]?");
    private static final Pattern GENERIC_API_KEY = Pattern.compile("(?i)(?:api_key|apikey|secret_key|client_secret|auth_token|access_token|private_key)\\s*[:=]\\s*['\"]([A-Za-z0-9_\\-]{16,})['\"]");
    private static final Pattern RSA_PRIVATE_KEY = Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern HARDCODED_PASSWORD = Pattern.compile("(?i)(?:password|passwd|db_password|spring\\.datasource\\.password)\\s*[:=]\\s*['\"]([^'\"\\r\\n\\${}]{3,})['\"]");
    private static final Pattern JWT_SECRET = Pattern.compile("(?i)(?:jwt\\.secret|jwt_secret|token\\.secret)\\s*[:=]\\s*['\"]([^'\"\\r\\n\\${}]{6,})['\"]");

    // Insecure Code Patterns
    private static final Pattern SQL_STRING_CONCAT = Pattern.compile("(?i)(?:createStatement\\(\\)|executeQuery|executeUpdate)\\s*\\(.*\\+.*\\)");
    private static final Pattern RUNTIME_EXEC = Pattern.compile("(?i)Runtime\\.getRuntime\\(\\)\\.exec\\(");
    private static final Pattern PROCESS_BUILDER = Pattern.compile("(?i)new\\s+ProcessBuilder\\(");
    private static final Pattern INSECURE_RANDOM = Pattern.compile("(?i)new\\s+java\\.util\\.Random\\(");
    private static final Pattern DISABLE_CSRF = Pattern.compile("(?i)\\.csrf\\(\\s*\\.disable\\(\\)");
    private static final Pattern PERMIT_ALL = Pattern.compile("(?i)\\.permitAll\\(\\)");

    // Insecure Docker Patterns
    private static final Pattern DOCKER_ROOT = Pattern.compile("(?i)^USER\\s+(root|0)\\s*$");
    private static final Pattern DOCKER_SOCK = Pattern.compile("(?i)/var/run/docker\\.sock");
    private static final Pattern DOCKER_PRIVILEGED = Pattern.compile("(?i)privileged\\s*:\\s*true");

    // Known Vulnerable Dependencies Patterns
    private static final Pattern LOG4J_VULN = Pattern.compile("<artifactId>log4j-core</artifactId>\\s*<version>2\\.(?:[0-9]|1[0-4]|15\\.0)</version>");
    private static final Pattern SPRING_VULN_V4S = Pattern.compile("<artifactId>spring-beans</artifactId>\\s*<version>5\\.(?:[0-2]\\.[0-9]+|3\\.(?:[0-9]|1[0-7]))</version>");
    private static final Pattern OLD_JACKSON = Pattern.compile("<artifactId>jackson-databind</artifactId>\\s*<version>2\\.(?:[0-8]\\.[0-9]+|9\\.(?:[0-9]|10\\.[0-7]))</version>");
    private static final Pattern OLD_COMMONS_COLLECTIONS = Pattern.compile("<artifactId>commons-collections</artifactId>\\s*<version>3\\.[0-2](\\.[0-9]+)?</version>");

    public SecurityResult scanRepository(Path repositoryPath) {
        List<SecurityFinding> findings = new ArrayList<>();

        try {
            Files.walkFileTree(repositoryPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules") || name.equals(".idea")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString().toLowerCase();
                    String relPath = repositoryPath.relativize(file).toString().replace('\\', '/');

                    if (attrs.size() > 2_000_000) { // skip large files > 2MB
                        return FileVisitResult.CONTINUE;
                    }

                    if (fileName.endsWith(".java") || fileName.endsWith(".kt") || fileName.endsWith(".py") ||
                            fileName.endsWith(".js") || fileName.endsWith(".ts") || fileName.endsWith(".xml") ||
                            fileName.endsWith(".yml") || fileName.endsWith(".yaml") || fileName.endsWith(".properties") ||
                            fileName.endsWith(".env") || fileName.equalsIgnoreCase("dockerfile") || fileName.contains("docker-compose")) {
                        scanFile(repositoryPath, file, relPath, findings);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error scanning repository security at {}: {}", repositoryPath, e.getMessage());
        }

        int critical = (int) findings.stream().filter(f -> f.severity() == SecuritySeverity.CRITICAL).count();
        int high = (int) findings.stream().filter(f -> f.severity() == SecuritySeverity.HIGH).count();
        int medium = (int) findings.stream().filter(f -> f.severity() == SecuritySeverity.MEDIUM).count();
        int low = (int) findings.stream().filter(f -> f.severity() == SecuritySeverity.LOW || f.severity() == SecuritySeverity.INFO).count();

        String grade;
        if (critical > 0) {
            grade = "F";
        } else if (high > 2) {
            grade = "D";
        } else if (high > 0) {
            grade = "C";
        } else if (medium > 2) {
            grade = "B";
        } else {
            grade = "A";
        }

        String summary = String.format("Security analysis complete: %d critical, %d high, %d medium, %d low issues found across scanned codebase. Overall Security Grade: %s.",
                critical, high, medium, low, grade);

        return new SecurityResult(grade, findings.size(), critical, high, medium, low, findings, summary);
    }

    private void scanFile(Path root, Path file, String relPath, List<SecurityFinding> findings) {
        try {
            List<String> lines = Files.readAllLines(file);
            String fullContent = String.join("\n", lines);

            // Whole file pattern checks
            if (relPath.endsWith("pom.xml")) {
                if (LOG4J_VULN.matcher(fullContent).find()) {
                    findings.add(new SecurityFinding(
                            UUID.randomUUID().toString(),
                            SecurityCategory.VULNERABLE_DEPENDENCY,
                            SecuritySeverity.CRITICAL,
                            "Vulnerable Log4j 2 dependency (CVE-2021-44228)",
                            "Log4j versions before 2.16.0 are vulnerable to remote code execution via JNDI injection.",
                            relPath,
                            null,
                            "log4j-core < 2.16.0",
                            "Upgrade org.apache.logging.log4j:log4j-core to 2.17.1 or newer."
                    ));
                }
                if (SPRING_VULN_V4S.matcher(fullContent).find()) {
                    findings.add(new SecurityFinding(
                            UUID.randomUUID().toString(),
                            SecurityCategory.VULNERABLE_DEPENDENCY,
                            SecuritySeverity.CRITICAL,
                            "Spring Framework RCE Vulnerability (Spring4Shell / CVE-2022-22965)",
                            "Vulnerable Spring Framework version allowing ClassLoader manipulation.",
                            relPath,
                            null,
                            "spring-beans vulnerable version",
                            "Upgrade Spring Framework to 5.3.18+ or Spring Boot to 2.6.6+ / 3.0+."
                    ));
                }
                if (OLD_JACKSON.matcher(fullContent).find()) {
                    findings.add(new SecurityFinding(
                            UUID.randomUUID().toString(),
                            SecurityCategory.VULNERABLE_DEPENDENCY,
                            SecuritySeverity.HIGH,
                            "Outdated Jackson Databind with Deserialization Gadgets",
                            "Older Jackson databind versions contain known polymorphic deserialization vulnerabilities.",
                            relPath,
                            null,
                            "jackson-databind < 2.10.0",
                            "Upgrade com.fasterxml.jackson.core:jackson-databind to latest patch release."
                    ));
                }
                if (OLD_COMMONS_COLLECTIONS.matcher(fullContent).find()) {
                    findings.add(new SecurityFinding(
                            UUID.randomUUID().toString(),
                            SecurityCategory.VULNERABLE_DEPENDENCY,
                            SecuritySeverity.HIGH,
                            "Vulnerable Commons Collections (InvokerTransformer Deserialization)",
                            "Commons Collections 3.2.1 and earlier contain arbitrary remote code execution gadgets.",
                            relPath,
                            null,
                            "commons-collections <= 3.2.1",
                            "Upgrade to org.apache.commons:commons-collections4:4.4 or commons-collections:3.2.2."
                    ));
                }
            }

            if (relPath.toLowerCase().contains("docker-compose") || relPath.toLowerCase().endsWith("dockerfile")) {
                if (DOCKER_SOCK.matcher(fullContent).find()) {
                    findings.add(new SecurityFinding(
                            UUID.randomUUID().toString(),
                            SecurityCategory.INSECURE_CONTAINER,
                            SecuritySeverity.CRITICAL,
                            "Docker Socket Mount Detected",
                            "Mounting /var/run/docker.sock allows containers to gain root control of the host Docker daemon.",
                            relPath,
                            null,
                            "/var/run/docker.sock",
                            "Avoid mounting the Docker socket inside non-infrastructure containers."
                    ));
                }
                if (DOCKER_PRIVILEGED.matcher(fullContent).find()) {
                    findings.add(new SecurityFinding(
                            UUID.randomUUID().toString(),
                            SecurityCategory.INSECURE_CONTAINER,
                            SecuritySeverity.HIGH,
                            "Privileged Container Execution",
                            "Container runs with privileged flag enabled, disabling Linux isolation primitives.",
                            relPath,
                            null,
                            "privileged: true",
                            "Remove privileged: true and grant only explicit Linux capabilities if needed."
                    ));
                }
            }

            // Line by line scanning
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNum = i + 1;

                if (RSA_PRIVATE_KEY.matcher(line).find()) {
                    findings.add(new SecurityFinding(
                            UUID.randomUUID().toString(),
                            SecurityCategory.SECRET_EXPOSURE,
                            SecuritySeverity.CRITICAL,
                            "Embedded Private Cryptographic Key",
                            "A private RSA/EC/SSH cryptographic key is committed directly into the source code.",
                            relPath,
                            lineNum,
                            "-----BEGIN PRIVATE KEY-----",
                            "Remove key immediately, rotate key pairs, and load keys via environment secrets or vault."
                    ));
                }

                Matcher awsMatcher = AWS_KEY.matcher(line);
                if (awsMatcher.find()) {
                    findings.add(new SecurityFinding(
                            UUID.randomUUID().toString(),
                            SecurityCategory.SECRET_EXPOSURE,
                            SecuritySeverity.CRITICAL,
                            "Potential AWS Access Key / Secret Exposed",
                            "Hardcoded AWS credential detected in file.",
                            relPath,
                            lineNum,
                            maskSecret(line.trim()),
                            "Store AWS credentials in AWS IAM roles or inject as environment variables."
                    ));
                }

                if (relPath.endsWith(".properties") || relPath.endsWith(".yml") || relPath.endsWith(".yaml")) {
                    Matcher pwdMatcher = HARDCODED_PASSWORD.matcher(line);
                    if (pwdMatcher.find() && !line.contains("${") && !pwdMatcher.group(1).contains("secret") && !pwdMatcher.group(1).contains("test") && !pwdMatcher.group(1).contains("example")) {
                        findings.add(new SecurityFinding(
                                UUID.randomUUID().toString(),
                                SecurityCategory.SECRET_EXPOSURE,
                                SecuritySeverity.HIGH,
                                "Hardcoded Database/Service Password in Config",
                                "Password appears hardcoded in cleartext rather than referenced via environment variable.",
                                relPath,
                                lineNum,
                                maskSecret(line.trim()),
                                "Use environment variable interpolation: password: ${DB_PASSWORD:default}."
                        ));
                    }

                    Matcher jwtMatcher = JWT_SECRET.matcher(line);
                    if (jwtMatcher.find() && !line.contains("${")) {
                        findings.add(new SecurityFinding(
                                UUID.randomUUID().toString(),
                                SecurityCategory.SECRET_EXPOSURE,
                                SecuritySeverity.HIGH,
                                "Hardcoded JWT Signing Secret",
                                "JWT token secret is hardcoded in application configuration.",
                                relPath,
                                lineNum,
                                maskSecret(line.trim()),
                                "Inject JWT secret key via environment variable or secret manager."
                        ));
                    }
                }

                if (relPath.endsWith(".java")) {
                    if (SQL_STRING_CONCAT.matcher(line).find()) {
                        findings.add(new SecurityFinding(
                                UUID.randomUUID().toString(),
                                SecurityCategory.INSECURE_CODE_PATTERN,
                                SecuritySeverity.HIGH,
                                "Potential SQL Injection via String Concatenation",
                                "SQL query appears to be constructed dynamically using string concatenation.",
                                relPath,
                                lineNum,
                                line.trim(),
                                "Use parameterized PreparedStatement queries or Spring Data JPA repositories."
                        ));
                    }

                    if (RUNTIME_EXEC.matcher(line).find() || PROCESS_BUILDER.matcher(line).find()) {
                        findings.add(new SecurityFinding(
                                UUID.randomUUID().toString(),
                                SecurityCategory.INSECURE_CODE_PATTERN,
                                SecuritySeverity.HIGH,
                                "Host OS Command Execution",
                                "Direct process execution found in Java code.",
                                relPath,
                                lineNum,
                                line.trim(),
                                "Avoid executing arbitrary shell commands or sanitize arguments with strict allowlists."
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed scanning file {}: {}", relPath, e.getMessage());
        }
    }

    private static String maskSecret(String text) {
        if (text == null || text.length() < 8) {
            return "***";
        }
        return text.substring(0, 4) + "...[REDACTED]";
    }
}
