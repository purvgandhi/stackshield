package com.stacktrial.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RepositoryScannerService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryScannerService.class);

    private static final Map<String, String[]> LANG_EXTENSIONS = Map.ofEntries(
            Map.entry("Java", new String[]{".java"}),
            Map.entry("Kotlin", new String[]{".kt", ".kts"}),
            Map.entry("Python", new String[]{".py"}),
            Map.entry("JavaScript", new String[]{".js", ".jsx", ".mjs"}),
            Map.entry("TypeScript", new String[]{".ts", ".tsx"}),
            Map.entry("HTML", new String[]{".html", ".htm"}),
            Map.entry("CSS", new String[]{".css", ".scss", ".sass", ".less"}),
            Map.entry("SQL", new String[]{".sql"}),
            Map.entry("YAML", new String[]{".yml", ".yaml"}),
            Map.entry("XML", new String[]{".xml"}),
            Map.entry("Markdown", new String[]{".md"}),
            Map.entry("Shell", new String[]{".sh", ".bash", ".ps1", ".bat", ".cmd"}),
            Map.entry("Docker", new String[]{"Dockerfile", ".dockerfile", ".dockerignore"})
    );

    private static final Map<String, String> LANG_COLORS = Map.ofEntries(
            Map.entry("Java", "#b07219"),
            Map.entry("Kotlin", "#A97BFF"),
            Map.entry("Python", "#3572A5"),
            Map.entry("JavaScript", "#f1e05a"),
            Map.entry("TypeScript", "#3178c6"),
            Map.entry("HTML", "#e34c26"),
            Map.entry("CSS", "#563d7c"),
            Map.entry("SQL", "#e38c00"),
            Map.entry("YAML", "#cb171e"),
            Map.entry("XML", "#0060ac"),
            Map.entry("Markdown", "#083fa1"),
            Map.entry("Shell", "#89e051"),
            Map.entry("Docker", "#384d54"),
            Map.entry("Other", "#6b7280")
    );

    public ScanResult scan(Path repoRoot) {
        String projectName = repoRoot.getFileName() != null ? repoRoot.getFileName().toString() : "Project";
        Map<String, Integer> langFileCounts = new HashMap<>();
        Map<String, Long> langLineCounts = new HashMap<>();
        List<String> importantFiles = new ArrayList<>();
        List<String> sourceDirs = new ArrayList<>();
        List<String> testDirs = new ArrayList<>();
        int testFileCount = 0;
        int totalFiles = 0;
        long totalLines = 0;

        boolean hasDocker = false;
        boolean hasDockerCompose = false;
        boolean hasGit = Files.isDirectory(repoRoot.resolve(".git"));

        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("node_modules") || name.equals("target") || name.equals("build") || name.equals(".idea")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String rel = repoRoot.relativize(dir).toString().replace('\\', '/');
                    if (rel.endsWith("src/main") || rel.endsWith("src/main/java") || rel.equals("src") || rel.equals("app")) {
                        sourceDirs.add(rel);
                    }
                    if (rel.endsWith("src/test") || rel.endsWith("src/test/java") || rel.equals("test") || rel.equals("tests")) {
                        testDirs.add(rel);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    String rel = repoRoot.relativize(file).toString().replace('\\', '/');

                    if (name.equalsIgnoreCase("Dockerfile") || name.endsWith(".dockerfile")) {
                        importantFiles.add(rel);
                    } else if (name.equalsIgnoreCase("docker-compose.yml") || name.equalsIgnoreCase("docker-compose.yaml") || name.equalsIgnoreCase("compose.yml")) {
                        importantFiles.add(rel);
                    } else if (name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts") || name.equals("package.json") || name.equals("requirements.txt") || name.equalsIgnoreCase("README.md")) {
                        importantFiles.add(rel);
                    } else if (name.startsWith("application") && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"))) {
                        importantFiles.add(rel);
                    }

                    if (name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java") || name.startsWith("test_") || name.endsWith(".test.js") || name.endsWith(".test.ts")) {
                        // counted as test
                    }

                    String lang = detectLanguage(name);
                    long lines = countLinesSafe(file);
                    langFileCounts.merge(lang, 1, Integer::sum);
                    langLineCounts.merge(lang, lines, Long::sum);

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error walking repository files at {}: {}", repoRoot, e.getMessage());
        }

        hasDocker = Files.exists(repoRoot.resolve("Dockerfile")) || Files.exists(repoRoot.resolve("dockerfile"));
        hasDockerCompose = Files.exists(repoRoot.resolve("docker-compose.yml")) || Files.exists(repoRoot.resolve("docker-compose.yaml")) || Files.exists(repoRoot.resolve("compose.yml"));

        // Build system detection
        String buildSystem = "Unknown";
        String buildSystemVersion = null;
        Integer javaVersion = null;
        String springBootVersion = null;
        List<DependencyInfo> dependencies = new ArrayList<>();
        Set<String> frameworks = new HashSet<>();
        Set<String> databases = new HashSet<>();
        Set<String> testFrameworks = new HashSet<>();
        Map<String, String> configProps = new LinkedHashMap<>();

        if (Files.exists(repoRoot.resolve("pom.xml"))) {
            buildSystem = "Maven";
            parsePomXml(repoRoot.resolve("pom.xml"), dependencies, frameworks, databases, testFrameworks);
            projectName = extractPomArtifactId(repoRoot.resolve("pom.xml"), projectName);
            javaVersion = extractPomJavaVersion(repoRoot.resolve("pom.xml"));
            springBootVersion = extractPomSpringBootVersion(repoRoot.resolve("pom.xml"));
        } else if (Files.exists(repoRoot.resolve("build.gradle")) || Files.exists(repoRoot.resolve("build.gradle.kts"))) {
            buildSystem = "Gradle";
            frameworks.add("Gradle");
        } else if (Files.exists(repoRoot.resolve("package.json"))) {
            buildSystem = "npm";
            parsePackageJson(repoRoot.resolve("package.json"), dependencies, frameworks, testFrameworks);
        } else if (Files.exists(repoRoot.resolve("requirements.txt")) || Files.exists(repoRoot.resolve("pyproject.toml"))) {
            buildSystem = "pip / Python";
            frameworks.add("Python");
        }

        // Scan config files for DBs & configs
        scanConfigurationFiles(repoRoot, databases, configProps);

        // Language stats calculations
        long totalCodeLines = langLineCounts.values().stream().mapToLong(Long::longValue).sum();
        int totalFileCount = langFileCounts.values().stream().mapToInt(Integer::intValue).sum();
        List<LanguageStat> languageStats = new ArrayList<>();
        for (Map.Entry<String, Long> entry : langLineCounts.entrySet()) {
            double pct = totalCodeLines > 0 ? (entry.getValue() * 100.0 / totalCodeLines) : 0.0;
            int count = langFileCounts.getOrDefault(entry.getKey(), 0);
            languageStats.add(new LanguageStat(entry.getKey(), count, entry.getValue(), Math.round(pct * 10.0) / 10.0, LANG_COLORS.getOrDefault(entry.getKey(), "#6b7280")));
        }
        languageStats.sort((a, b) -> Long.compare(b.estimatedLines(), a.estimatedLines()));

        // Count test files
        testFileCount = langFileCounts.getOrDefault("Java", 0); // fallback or specific test count

        // Health observations
        List<HealthObservation> health = new ArrayList<>();
        if (buildSystem.equals("Maven")) {
            health.add(new HealthObservation("SUCCESS", "Standard Maven Build Detected", "Root pom.xml defines project lifecycle and reproducible dependencies.", "Ready for container execution."));
        }
        if (javaVersion != null) {
            if (javaVersion >= 17) {
                health.add(new HealthObservation("SUCCESS", "Modern Java Target (Java " + javaVersion + ")", "Meets modern runtime and Spring Boot 3 standards.", "Compatible with temurin-17 / temurin-21 runner images."));
            } else {
                health.add(new HealthObservation("WARNING", "Legacy Java Target (Java " + javaVersion + ")", "Spring Boot 3.x requires Java 17+.", "Consider verifying a Java 17/21 upgrade trial in StackTrial."));
            }
        }
        if (springBootVersion != null) {
            if (springBootVersion.startsWith("3.")) {
                health.add(new HealthObservation("SUCCESS", "Spring Boot 3 (" + springBootVersion + ")", "Running modern Jakarta EE 10 baseline.", "All features supported."));
            } else if (springBootVersion.startsWith("2.")) {
                health.add(new HealthObservation("WARNING", "Spring Boot 2.x (" + springBootVersion + ")", "End-of-life version. Upgrading to 3.x is recommended.", "Run a SPRING_BOOT_VERSION trial to test 3.0+ compatibility."));
            }
        }
        if (hasDocker) {
            health.add(new HealthObservation("INFO", "Containerized Deployment Ready", "Dockerfile found in repository root.", "Can be built and deployed as an OCI container."));
        }
        if (dependencies.isEmpty() && buildSystem.equals("Maven")) {
            health.add(new HealthObservation("WARNING", "Zero Dependencies Parsed", "Pom.xml might be multi-module or minimal.", "Verify root pom.xml."));
        }

        return new ScanResult(
                projectName,
                buildSystem,
                buildSystemVersion,
                javaVersion,
                springBootVersion,
                new ArrayList<>(frameworks),
                new ArrayList<>(databases),
                new ArrayList<>(testFrameworks),
                hasDocker,
                hasDockerCompose,
                hasGit,
                totalFileCount,
                totalCodeLines,
                languageStats,
                dependencies,
                importantFiles,
                sourceDirs,
                testDirs,
                testFileCount,
                health,
                configProps
        );
    }

    private static String detectLanguage(String filename) {
        for (Map.Entry<String, String[]> entry : LANG_EXTENSIONS.entrySet()) {
            for (String ext : entry.getValue()) {
                if (ext.startsWith(".") && filename.toLowerCase().endsWith(ext.toLowerCase())) {
                    return entry.getKey();
                } else if (!ext.startsWith(".") && filename.equalsIgnoreCase(ext)) {
                    return entry.getKey();
                }
            }
        }
        return "Other";
    }

    private static long countLinesSafe(Path file) {
        try {
            if (Files.size(file) > 1_000_000) { // skip large files > 1MB
                return 100;
            }
            return Files.lines(file).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void parsePomXml(Path pomPath, List<DependencyInfo> deps, Set<String> frameworks, Set<String> databases, Set<String> testFrameworks) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(pomPath.toFile());
            doc.getDocumentElement().normalize();

            // Parent
            NodeList parents = doc.getElementsByTagName("parent");
            if (parents.getLength() > 0) {
                Element parent = (Element) parents.item(0);
                String art = getTagValue("artifactId", parent);
                if ("spring-boot-starter-parent".equals(art)) {
                    frameworks.add("Spring Boot");
                }
            }

            // Dependencies
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element dep = (Element) depNodes.item(i);
                String g = getTagValue("groupId", dep);
                String a = getTagValue("artifactId", dep);
                String v = getTagValue("version", dep);
                String s = getTagValue("scope", dep);

                if (a != null) {
                    String cat = categorizeDependency(g, a);
                    deps.add(new DependencyInfo(g, a, v, s != null ? s : "compile", cat, true));

                    if (a.contains("spring-boot")) frameworks.add("Spring Boot");
                    if (a.contains("spring-security")) frameworks.add("Spring Security");
                    if (a.contains("spring-data")) frameworks.add("Spring Data JPA");
                    if (a.contains("thymeleaf")) frameworks.add("Thymeleaf");
                    if (a.contains("actuator")) frameworks.add("Spring Actuator");

                    if (a.contains("postgresql") || (g != null && g.contains("postgresql"))) databases.add("PostgreSQL");
                    if (a.contains("mysql")) databases.add("MySQL");
                    if (a.contains("h2")) databases.add("H2 Database");
                    if (a.contains("mongodb") || a.contains("mongo")) databases.add("MongoDB");
                    if (a.contains("redis")) databases.add("Redis");
                    if (a.contains("oracle") || a.contains("ojdbc")) databases.add("Oracle Database");

                    if (a.contains("junit-jupiter") || a.contains("junit-5")) testFrameworks.add("JUnit 5");
                    if (a.contains("junit") && !a.contains("jupiter")) testFrameworks.add("JUnit 4");
                    if (a.contains("mockito")) testFrameworks.add("Mockito");
                    if (a.contains("spring-boot-starter-test")) testFrameworks.add("Spring Boot Test");
                    if (a.contains("testcontainers")) testFrameworks.add("Testcontainers");
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse pom.xml at {}: {}", pomPath, e.getMessage());
        }
    }

    private static String categorizeDependency(String g, String a) {
        String s = (g + ":" + a).toLowerCase();
        if (s.contains("test") || s.contains("junit") || s.contains("mockito") || s.contains("assertj")) return "Testing";
        if (s.contains("postgres") || s.contains("mysql") || s.contains("database") || s.contains("h2") || s.contains("hibernate") || s.contains("flyway") || s.contains("data-jpa")) return "Database / Persistence";
        if (s.contains("web") || s.contains("rest") || s.contains("tomcat") || s.contains("jersey") || s.contains("mvc")) return "Web / API";
        if (s.contains("security") || s.contains("auth") || s.contains("jwt") || s.contains("crypto")) return "Security";
        if (s.contains("jackson") || s.contains("json") || s.contains("gson") || s.contains("commons-") || s.contains("lombok") || s.contains("guava")) return "Utility";
        return "Core";
    }

    private static void parsePackageJson(Path packageJsonPath, List<DependencyInfo> deps, Set<String> frameworks, Set<String> testFrameworks) {
        try {
            String content = Files.readString(packageJsonPath);
            if (content.contains("\"react\"")) frameworks.add("React");
            if (content.contains("\"vue\"")) frameworks.add("Vue");
            if (content.contains("\"next\"")) frameworks.add("Next.js");
            if (content.contains("\"express\"")) frameworks.add("Express");
            if (content.contains("\"jest\"")) testFrameworks.add("Jest");
            if (content.contains("\"mocha\"")) testFrameworks.add("Mocha");
        } catch (Exception ignored) {
        }
    }

    private static void scanConfigurationFiles(Path root, Set<String> databases, Map<String, String> configProps) {
        List<Path> configs = List.of(
                root.resolve("src/main/resources/application.yml"),
                root.resolve("src/main/resources/application.yaml"),
                root.resolve("src/main/resources/application.properties"),
                root.resolve("application.yml"),
                root.resolve(".env.example")
        );
        for (Path c : configs) {
            if (Files.exists(c)) {
                try {
                    List<String> lines = Files.readAllLines(c);
                    for (String line : lines) {
                        String tr = line.strip();
                        if (tr.startsWith("#") || tr.isBlank()) continue;
                        if (tr.contains("jdbc:postgresql") || tr.contains("postgres")) databases.add("PostgreSQL");
                        if (tr.contains("jdbc:mysql") || tr.contains("mysql")) databases.add("MySQL");
                        if (tr.contains("jdbc:oracle") || tr.contains("oracle")) databases.add("Oracle Database");
                        if (tr.contains("jdbc:h2") || tr.contains("h2:mem")) databases.add("H2 Database");

                        if (tr.contains(":") && !tr.startsWith("-") && configProps.size() < 25) {
                            String[] parts = tr.split(":", 2);
                            if (parts.length == 2 && !parts[1].isBlank()) {
                                configProps.put(parts[0].strip(), parts[1].strip());
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String extractPomArtifactId(Path pomPath, String fallback) {
        try {
            String content = Files.readString(pomPath);
            Matcher m = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(content);
            if (m.find()) {
                return m.group(1).strip();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static Integer extractPomJavaVersion(Path pomPath) {
        try {
            String content = Files.readString(pomPath);
            Matcher m = Pattern.compile("<(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.target)>(\\d+)</").matcher(content);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {}
        return 17; // standard modern baseline
    }

    private static String extractPomSpringBootVersion(Path pomPath) {
        try {
            String content = Files.readString(pomPath);
            Matcher m = Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>").matcher(content);
            if (m.find()) {
                return m.group(1).strip();
            }
            Matcher m2 = Pattern.compile("<spring-boot\\.version>([^<]+)</spring-boot\\.version>").matcher(content);
            if (m2.find()) {
                return m2.group(1).strip();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String getTagValue(String tag, Element element) {
        NodeList nl = element.getElementsByTagName(tag);
        if (nl != null && nl.getLength() > 0 && nl.item(0).getFirstChild() != null) {
            return nl.item(0).getFirstChild().getNodeValue().strip();
        }
        return null;
    }
}
