package com.stacktrial.architecture;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ArchitectureExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureExtractorService.class);

    private static final Pattern CLASS_DEF = Pattern.compile("(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?(class|interface|record|enum)\\s+([A-Za-z0-9_]+)");
    private static final Pattern PACKAGE_DEF = Pattern.compile("package\\s+([A-Za-z0-9_.]+);");
    private static final Pattern REQ_MAPPING = Pattern.compile("@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)(?:\\s*\\(\\s*(?:value\\s*=\\s*)?(?:path\\s*=\\s*)?[\"']?([^\"')\\s]+)[\"']?)?");
    private static final Pattern MAPPING_METHOD = Pattern.compile("@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)(?:\\s*\\(\\s*(?:value\\s*=\\s*)?(?:path\\s*=\\s*)?[\"']?([^\"')\\s]*)[\"']?)?\\s*(?:public\\s+)?[A-Za-z0-9_<>\\[\\],\\s]+\\s+([A-Za-z0-9_]+)\\s*\\(");

    public ArchitectureResult extract(Path repoRoot) {
        List<ArchitectureNode> nodes = new ArrayList<>();
        List<ApiEndpointInfo> endpoints = new ArrayList<>();
        Set<String> externalIntegrations = new HashSet<>();
        Map<String, List<String>> packageHierarchy = new HashMap<>();

        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules") || name.equals("build") || name.equals(".idea")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java") && !name.endsWith("Test.java") && !name.endsWith("Tests.java") && !name.endsWith("IT.java")) {
                        try {
                            analyzeJavaFile(file, repoRoot, nodes, endpoints, externalIntegrations, packageHierarchy);
                        } catch (Exception e) {
                            log.debug("Error analyzing Java file {}: {}", file, e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed scanning repository for architecture: {}", e.getMessage());
        }

        // Link dependencies / targets between nodes based on field injection or class mentions
        linkNodeTargets(nodes, repoRoot);

        int controllers = (int) nodes.stream().filter(n -> "CONTROLLER".equals(n.role())).count();
        int services = (int) nodes.stream().filter(n -> "SERVICE".equals(n.role())).count();
        int repos = (int) nodes.stream().filter(n -> "REPOSITORY".equals(n.role())).count();
        int entities = (int) nodes.stream().filter(n -> "ENTITY".equals(n.role())).count();
        int configs = (int) nodes.stream().filter(n -> "CONFIGURATION".equals(n.role())).count();

        String style = "Layered Architecture";
        if (controllers > 0 && services > 0 && repos > 0) {
            style = "Spring Boot 3-Tier Layered Monolith (Controller → Service → Repository → Entity)";
        } else if (controllers > 0 && repos > 0) {
            style = "REST API with Direct Data Access";
        }

        String summary = String.format("Detected %d components across %d packages: %d Controllers, %d Services, %d Repositories, %d Entities, exposing %d API endpoints.",
                nodes.size(), packageHierarchy.size(), controllers, services, repos, entities, endpoints.size());

        return new ArchitectureResult(
                style,
                nodes.size(),
                controllers,
                services,
                repos,
                entities,
                configs,
                endpoints.size(),
                nodes,
                endpoints,
                new ArrayList<>(externalIntegrations),
                packageHierarchy,
                summary
        );
    }

    private void analyzeJavaFile(Path file, Path repoRoot, List<ArchitectureNode> nodes, List<ApiEndpointInfo> endpoints,
                                 Set<String> externalIntegrations, Map<String, List<String>> packageHierarchy) throws IOException {
        String content = Files.readString(file);
        String relPath = repoRoot.relativize(file).toString().replace('\\', '/');

        Matcher pkgMatcher = PACKAGE_DEF.matcher(content);
        String pkg = pkgMatcher.find() ? pkgMatcher.group(1) : "default";
        packageHierarchy.computeIfAbsent(pkg, k -> new ArrayList<>()).add(file.getFileName().toString());

        Matcher classMatcher = CLASS_DEF.matcher(content);
        if (!classMatcher.find()) {
            return;
        }
        String className = classMatcher.group(2);

        List<String> annotations = new ArrayList<>();
        String role = "COMPONENT";
        String layer = "SERVICE";
        String detectionType = "INFERRED";

        if (content.contains("@RestController") || content.contains("@Controller")) {
            role = "CONTROLLER";
            layer = "API";
            detectionType = "DETECTED";
            annotations.add(content.contains("@RestController") ? "@RestController" : "@Controller");
            extractEndpoints(content, className, endpoints);
        } else if (content.contains("@Service")) {
            role = "SERVICE";
            layer = "SERVICE";
            detectionType = "DETECTED";
            annotations.add("@Service");
        } else if (content.contains("@Repository") || content.contains("extends JpaRepository") || content.contains("extends CrudRepository")) {
            role = "REPOSITORY";
            layer = "PERSISTENCE";
            detectionType = "DETECTED";
            annotations.add("@Repository");
        } else if (content.contains("@Entity") || content.contains("@Table") || content.contains("@Document")) {
            role = "ENTITY";
            layer = "DOMAIN";
            detectionType = "DETECTED";
            annotations.add("@Entity");
        } else if (content.contains("@Configuration") || content.contains("@SpringBootApplication")) {
            role = "CONFIGURATION";
            layer = "CONFIG";
            detectionType = "DETECTED";
            annotations.add(content.contains("@SpringBootApplication") ? "@SpringBootApplication" : "@Configuration");
        } else if (className.endsWith("Controller")) {
            role = "CONTROLLER";
            layer = "API";
            detectionType = "INFERRED";
        } else if (className.endsWith("Service")) {
            role = "SERVICE";
            layer = "SERVICE";
            detectionType = "INFERRED";
        } else if (className.endsWith("Repository") || className.endsWith("Dao")) {
            role = "REPOSITORY";
            layer = "PERSISTENCE";
            detectionType = "INFERRED";
        }

        if (content.contains("RestTemplate") || content.contains("WebClient") || content.contains("RestClient") || content.contains("@FeignClient")) {
            externalIntegrations.add("External HTTP / REST API (WebClient/RestTemplate)");
        }
        if (content.contains("@KafkaListener") || content.contains("KafkaTemplate")) {
            externalIntegrations.add("Apache Kafka Event Stream");
        }
        if (content.contains("DataSource") || content.contains("EntityManager") || content.contains("JdbcTemplate")) {
            externalIntegrations.add("RDBMS / PostgreSQL Database");
        }
        if (content.contains("DockerClient") || content.contains("docker-java")) {
            externalIntegrations.add("Docker Engine API");
        }
        if (content.contains("GeminiClient") || content.contains("generativelanguage.googleapis.com")) {
            externalIntegrations.add("Google Gemini AI REST API");
        }

        nodes.add(new ArchitectureNode(
                className,
                className,
                layer,
                role,
                pkg,
                relPath,
                detectionType,
                annotations,
                new ArrayList<>()
        ));
    }

    private void extractEndpoints(String content, String controllerName, List<ApiEndpointInfo> endpoints) {
        String basePath = "";
        Matcher baseMatcher = Pattern.compile("@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?(?:path\\s*=\\s*)?[\"']([^\"']+)[\"']").matcher(content);
        if (baseMatcher.find()) {
            basePath = baseMatcher.group(1);
            if (!basePath.startsWith("/")) basePath = "/" + basePath;
            if (basePath.endsWith("/")) basePath = basePath.substring(0, basePath.length() - 1);
        }

        Matcher m = MAPPING_METHOD.matcher(content);
        while (m.find()) {
            String mappingType = m.group(1);
            String path = m.group(2) != null ? m.group(2) : "";
            String methodName = m.group(3);

            if (!path.isEmpty() && !path.startsWith("/")) path = "/" + path;
            String fullPath = basePath + path;
            if (fullPath.isEmpty()) fullPath = "/";

            String httpMethod = "GET";
            if (mappingType.startsWith("Post")) httpMethod = "POST";
            else if (mappingType.startsWith("Put")) httpMethod = "PUT";
            else if (mappingType.startsWith("Delete")) httpMethod = "DELETE";
            else if (mappingType.startsWith("Patch")) httpMethod = "PATCH";

            endpoints.add(new ApiEndpointInfo(httpMethod, fullPath, controllerName, methodName, "Handled by " + controllerName + "." + methodName + "()"));
        }
    }

    private void linkNodeTargets(List<ArchitectureNode> nodes, Path repoRoot) {
        Map<String, ArchitectureNode> byName = new HashMap<>();
        for (ArchitectureNode node : nodes) {
            byName.put(node.name(), node);
        }

        for (int i = 0; i < nodes.size(); i++) {
            ArchitectureNode node = nodes.get(i);
            try {
                Path file = repoRoot.resolve(node.filePath());
                if (Files.exists(file)) {
                    String src = Files.readString(file);
                    List<String> targets = new ArrayList<>();
                    for (String otherName : byName.keySet()) {
                        if (!otherName.equals(node.name()) && src.contains(otherName)) {
                            targets.add(otherName);
                        }
                    }
                    nodes.set(i, new ArchitectureNode(
                            node.id(), node.name(), node.layer(), node.role(), node.packageName(),
                            node.filePath(), node.detectionType(), node.annotations(), targets
                    ));
                }
            } catch (Exception ignored) {
            }
        }
    }
}
