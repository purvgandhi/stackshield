package com.stacktrial.explanation;

import com.stacktrial.PostgresTestcontainer;
import com.stacktrial.comparison.EvidenceItem;
import com.stacktrial.comparison.EvidenceKind;
import com.stacktrial.comparison.Finding;
import com.stacktrial.comparison.FindingType;
import com.stacktrial.comparison.Severity;
import com.stacktrial.comparison.Side;
import com.stacktrial.comparison.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Gemini is replaced by a recording stub; retrieval, prompt building, citation checks and storage are real. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, ExplanationServiceIT.StubLlm.class})
class ExplanationServiceIT {

    static final AtomicReference<String> lastPrompt = new AtomicReference<>();

    @TestConfiguration
    static class StubLlm {
        @Bean
        LlmClient stubLlm() {
            return new LlmClient() {
                public String modelName() { return "stub"; }
                public String generate(String system, String prompt) {
                    lastPrompt.set(system + "\n" + prompt);
                    return "SUMMARY\nThe trailing-slash test now returns 404 [EVIDENCE-002] as documented [KB-SB30-03]. [KB-FAKE-01]";
                }
            };
        }
    }

    @Autowired
    ExplanationService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;

    @Test
    void explanationIsGroundedAndCitationsAreValidated() throws Exception {
        UUID trialId = insertTrial();
        ExplanationInput input = new ExplanationInput(trialId, "Upgrade Spring Boot to 3.0.13", Verdict.REGRESSION,
                "1 test(s) passed in baseline but failed in trial.",
                List.of(new Finding(FindingType.TEST_REGRESSION, Severity.HIGH, "Test regressed: OrderControllerTest#listsOrdersWithTrailingSlash",
                        "Passed in baseline, FAILED in trial", List.of("EVIDENCE-001"), List.of("EVIDENCE-002"))),
                List.of(new EvidenceItem("EVIDENCE-001", EvidenceKind.TEST_PASS, Side.BASELINE, "Baseline test passed", "PASSED"),
                        new EvidenceItem("EVIDENCE-002", EvidenceKind.TEST_FAILURE, Side.TRIAL, "Trial test FAILED",
                                "java.lang.AssertionError: Status expected:<200> but was:<404> password=hunter2")));

        Explanation e = service.explain(input);

        assertThat(e.getStatus()).isEqualTo(ExplanationStatus.GENERATED);
        assertThat(e.getRetrievedChunks()).contains("KB-SB30-03");
        assertThat(e.getCitedEvidence()).containsExactly("EVIDENCE-002");
        assertThat(e.getCitedKnowledge()).containsExactly("KB-SB30-03");
        assertThat(e.getUnknownCitations()).containsExactly("KB-FAKE-01");
        assertThat(lastPrompt.get()).contains("VERDICT (final, computed deterministically)", "[KB-SB30-03]")
                .doesNotContain("hunter2");

        // the stored explanation is what the report API and page show
        mvc.perform(get("/api/trials/{id}", trialId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation.status").value("GENERATED"))
                .andExpect(jsonPath("$.explanation.unknownCitations[0]").value("KB-FAKE-01"));
        mvc.perform(get("/trials/{id}", trialId)).andExpect(status().isOk())
                .andExpect(content().string(containsString("The trailing-slash test now returns 404")))
                .andExpect(content().string(containsString("KB-FAKE-01")));
    }

    @Test
    void passVerdictNeedsNoExplanation() {
        UUID trialId = insertTrial();
        Explanation e = service.explain(new ExplanationInput(trialId, "x", Verdict.PASS, "ok", List.of(), List.of()));
        assertThat(e.getStatus()).isEqualTo(ExplanationStatus.NOT_NEEDED);
    }

    private UUID insertTrial() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO trial (id, repo_url, baseline_ref, change_type, change_spec, status, created_at, updated_at) "
                + "VALUES (?, 'https://x/y', 'main', 'JAVA_VERSION', '{}'::jsonb, 'COMPLETED', ?, ?)", id, now, now);
        return id;
    }
}
