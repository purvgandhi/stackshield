package com.stacktrial.interview;

import com.stacktrial.architecture.ArchitectureNode;
import com.stacktrial.architecture.ArchitectureResult;
import com.stacktrial.scanner.ScanResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class InterviewService {

    public InterviewResult generateQuestions(ScanResult scanResult, ArchitectureResult architecture) {
        List<InterviewQuestion> questions = new ArrayList<>();

        String framework = (scanResult != null && scanResult.springBootVersion() != null)
                ? "Spring Boot " + scanResult.springBootVersion()
                : "Spring Boot";

        // 1. Architecture & Layering
        List<String> controllerNames = new ArrayList<>();
        List<String> serviceNames = new ArrayList<>();
        List<String> repoNames = new ArrayList<>();

        if (architecture != null && architecture.nodes() != null) {
            for (ArchitectureNode node : architecture.nodes()) {
                if ("CONTROLLER".equals(node.role())) controllerNames.add(node.name());
                if ("SERVICE".equals(node.role())) serviceNames.add(node.name());
                if ("REPOSITORY".equals(node.role())) repoNames.add(node.name());
            }
        }

        String sampleController = controllerNames.isEmpty() ? "ApiController" : controllerNames.get(0);
        String sampleService = serviceNames.isEmpty() ? "BusinessService" : serviceNames.get(0);
        String sampleRepo = repoNames.isEmpty() ? "DataRepository" : repoNames.get(0);

        questions.add(new InterviewQuestion(
                UUID.randomUUID().toString(),
                "ARCHITECTURE",
                "INTERMEDIATE",
                "Explain the end-to-end request flow from `" + sampleController + "` through `" + sampleService + "` down to the persistence layer. How is separation of concerns enforced?",
                "Describe how HTTP requests are mapped, how DTOs are validated and transformed, how transactional business logic executes in the service tier, and how repository abstractions isolate data access.",
                List.of(sampleController + ".java", sampleService + ".java", sampleRepo + ".java"),
                List.of(
                        "Controller layer handles HTTP serialization, input validation, and HTTP status codes.",
                        "Service layer holds domain invariants, orchestration, and `@Transactional` boundaries.",
                        "Repository layer encapsulates queries and ORM mapping."
                )
        ));

        // 2. Concurrency & Thread Safety
        questions.add(new InterviewQuestion(
                UUID.randomUUID().toString(),
                "CONCURRENCY",
                "ADVANCED",
                "How does " + framework + " handle concurrent web requests across singleton Spring beans like `" + sampleService + "`? What concurrency hazards must developers watch out for?",
                "Spring bean singletons are shared across all concurrent request threads. State within singleton fields must be thread-safe or immutable. Explain ThreadLocal usage, stateless services, and connection pooling.",
                List.of(sampleService + ".java"),
                List.of(
                        "Spring Beans are singletons by default and must remain stateless.",
                        "Request-scoped state should be passed as method arguments or managed via request context.",
                        "Database connection pools (HikariCP) govern concurrent transactional throughput."
                )
        ));

        // 3. Database & Transactions
        if (!repoNames.isEmpty() || (scanResult != null && !scanResult.detectedDatabases().isEmpty())) {
            questions.add(new InterviewQuestion(
                    UUID.randomUUID().toString(),
                    "DATABASE",
                    "INTERMEDIATE",
                    "How are database transactions and isolation levels configured in this repository? What happens if an unchecked vs checked exception is thrown inside a service method?",
                    "Spring `@Transactional` rolls back by default on `RuntimeException` and `Error`, but commits on checked `Exception` unless `rollbackFor` is explicitly declared.",
                    List.of(sampleService + ".java", sampleRepo + ".java"),
                    List.of(
                            "Default rollback behavior: RuntimeException triggers rollback, checked Exception does not.",
                            "Transaction propagation: REQUIRED vs REQUIRES_NEW for isolated sub-transactions.",
                            "First-level Hibernate cache vs database write-behind (flush)."
                    )
            ));
        }

        // 4. Error Handling & Resilience
        questions.add(new InterviewQuestion(
                UUID.randomUUID().toString(),
                "ERROR_HANDLING",
                "INTERMEDIATE",
                "How does this codebase handle uncaught exceptions, validation failures, and upstream timeouts? How would you implement centralized error handling?",
                "Explain `@RestControllerAdvice` and `@ExceptionHandler`, RFC 7807 Problem Details for HTTP APIs, and how to avoid leaking internal stack traces to API consumers.",
                List.of("GlobalExceptionHandler.java", sampleController + ".java"),
                List.of(
                        "`@RestControllerAdvice` provides global interception for REST controllers.",
                        "Standardized error JSON payload format (RFC 7807).",
                        "Sanitizing internal exception messages from public production responses."
                )
        ));

        // 5. System Design & Scalability
        questions.add(new InterviewQuestion(
                UUID.randomUUID().toString(),
                "SYSTEM_DESIGN",
                "ADVANCED",
                "If this service receives a 50x spike in traffic, what are the primary bottlenecks (CPU, connection pool, I/O, locks) and how would you scale it horizontally?",
                "Discuss stateless horizontal replica scaling behind a load balancer, database read replicas / query caching, connection pool sizing, and asynchronous job processing.",
                List.of("application.yml", "Dockerfile"),
                List.of(
                        "Horizontal pod autoscaling (HPA) and stateless application pods.",
                        "Database connection pool sizing relative to database server CPU cores.",
                        "Offloading long-running heavy work to background queues / asynchronous processing."
                )
        ));

        String summary = String.format("Generated %d repository-grounded technical interview and deep-dive questions based on extracted architecture and tech stack.", questions.size());

        return new InterviewResult(questions.size(), questions, summary);
    }
}
