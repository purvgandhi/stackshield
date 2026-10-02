package com.stacktrial.trial;

import com.stacktrial.PostgresTestcontainer;
import com.stacktrial.execution.ContainerRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full trial lifecycle through the REST API with a real database, real git clone, real change application,
 * real parsing and comparison. Only the Docker boundary is replaced (see {@link FakeContainerRunner}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TrialLifecycleIT.FakeDocker.class})
class TrialLifecycleIT {

    @TempDir
    static Path tmp;
    static String repoUrl;

    @Autowired
    MockMvc mvc;
    @Autowired
    TrialReportService reports;
    @Autowired
    TrialStore store;

    @TestConfiguration
    static class FakeDocker {
        @Bean
        @Primary
        ContainerRunner fakeContainerRunner() {
            return new FakeContainerRunner();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("stacktrial.data-dir", () -> tmp.resolve("data").toString());
    }

    @BeforeAll
    static void repo() throws Exception {
        repoUrl = FixtureRepository.create("breaking-upgrade", java.nio.file.Files.createDirectories(tmp.resolve("repo")));
    }

    @Test
    void createsTrialAsynchronouslyAndProducesDeterministicReport() throws Exception {
        String body = """
                {"repoUrl":"%s","baselineRef":"main","changeType":"SPRING_BOOT_VERSION","targetVersion":"3.0.13"}
                """.formatted(repoUrl);

        String response = mvc.perform(post("/api/trials").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.trialId"));

        TrialReport report = awaitFinished(id);

        assertThat(report.status()).as(report.failureReason()).isEqualTo("COMPLETED");
        assertThat(report.verdict()).isEqualTo(com.stacktrial.comparison.Verdict.REGRESSION);
        assertThat(report.environment().baselineSpringBoot()).isEqualTo("2.7.18");
        assertThat(report.environment().trialSpringBoot()).isEqualTo("3.0.13");
        assertThat(report.appliedDiff()).contains("+        <version>3.0.13</version>");
        assertThat(report.findings()).extracting(f -> f.type().name()).contains("BUILD_REGRESSION", "TEST_REGRESSION", "DEPENDENCY_CHANGED");
        assertThat(report.evidence()).extracting(e -> e.code()).startsWith("EVIDENCE-001");
        assertThat(report.failedTests()).singleElement().satisfies(t -> assertThat(t.test()).endsWith("#listsOrdersWithTrailingSlash"));
        assertThat(report.explanation().status()).isEqualTo("UNAVAILABLE");
        assertThat(report.explanation().documentation()).extracting(TrialReport.KnowledgeRef::id).contains("KB-SB30-03");

        mvc.perform(get("/api/trials/{id}/runs/TRIAL/log", id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("There are test failures")));
        mvc.perform(get("/api/trials/{id}/runs/TRIAL/dependency-tree", id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("spring-webmvc:jar:6.0.14")));
        mvc.perform(get("/api/trials")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].verdict".formatted(id)).value(org.hamcrest.Matchers.hasItem("REGRESSION")));
        mvc.perform(get("/trials/{id}", id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("REGRESSION")))
                .andExpect(content().string(containsString("Why did it fail?")));
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(containsString("Upgrade Spring Boot to 3.0.13")));
    }

    @Test
    void progressPageRendersWhileTrialIsRunning() throws Exception {
        Trial trial = store.save(Trial.queued(repoUrl, "main",
                new com.stacktrial.workspace.change.ChangeRequest(com.stacktrial.workspace.change.ChangeType.JAVA_VERSION, null, null, "21", null)));
        mvc.perform(get("/trials/{id}", trial.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Trial in progress")));

        store.moveTo(trial.getId(), TrialStatus.RUNNING, "Running baseline");
        mvc.perform(get("/trials/{id}", trial.getId())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Running baseline")))
                .andExpect(content().string(containsString("class=\"step done\"")));
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(post("/api/trials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"ssh://evil\",\"baselineRef\":\"main\",\"changeType\":\"JAVA_VERSION\",\"targetVersion\":\"21\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
        mvc.perform(post("/api/trials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoUrl\":\"%s\",\"baselineRef\":\"main\",\"changeType\":\"DEPENDENCY_VERSION\"}".formatted(repoUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("groupId")));
        mvc.perform(get("/api/trials/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void invalidDiffEndsAsFailedTrialWithReason() throws Exception {
        String body = """
                {"repoUrl":"%s","baselineRef":"main","changeType":"GIT_DIFF","diff":"diff --git a/x b/x\\n--- a/x\\n+++ b/x\\n@@ -1 +1 @@\\n-a\\n+b\\n"}
                """.formatted(repoUrl);
        String response = mvc.perform(post("/api/trials").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        TrialReport report = awaitFinished(UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.trialId")));

        assertThat(report.status()).isEqualTo("FAILED");
        assertThat(report.failureReason()).contains("Diff does not apply cleanly");
        assertThat(report.verdict()).isNull();
    }

    private TrialReport awaitFinished(UUID id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        TrialReport report = reports.report(id);
        while (!report.finished() && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            report = reports.report(id);
        }
        return report;
    }
}
