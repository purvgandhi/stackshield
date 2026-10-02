package com.stacktrial.trial;

import com.stacktrial.PostgresTestcontainer;
import com.stacktrial.comparison.FindingType;
import com.stacktrial.comparison.Verdict;
import com.stacktrial.workspace.change.ChangeRequest;
import com.stacktrial.workspace.change.ChangeType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The complete demo path with nothing replaced: git clone, change, two real Docker containers running mvn verify,
 * Surefire parsing, comparison, retrieval, report. Opt-in because it needs Docker and network access for Maven:
 * <pre>mvn verify -Pe2e</pre>
 */
@Tag("e2e")
@SpringBootTest
@Import(PostgresTestcontainer.class)
class RealDockerE2ETest {

    @TempDir
    static Path tmp;

    @Autowired
    TrialService trials;
    @Autowired
    TrialReportService reports;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("stacktrial.data-dir", () -> tmp.resolve("data").toString());
    }

    @Test
    void springBootUpgradeOfFixtureBShowsRealRegression() throws Exception {
        String repo = FixtureRepository.create("breaking-upgrade", Files.createDirectories(tmp.resolve("repo")));

        Trial trial = trials.create(repo, "main", new ChangeRequest(ChangeType.SPRING_BOOT_VERSION, null, null, "3.0.13", null));
        TrialReport report = await(trial, Duration.ofMinutes(20));

        assertThat(report.status()).as(report.failureReason()).isEqualTo("COMPLETED");
        assertThat(report.verdict()).isEqualTo(Verdict.REGRESSION);
        assertThat(report.baseline().outcome()).isEqualTo("SUCCESS");
        assertThat(report.baseline().tests().failed()).isZero();
        assertThat(report.trial().tests().failed()).isEqualTo(2);
        assertThat(report.findings()).filteredOn(f -> f.type() == FindingType.TEST_REGRESSION)
                .extracting(f -> f.title())
                .allMatch(title -> title.contains("TrailingSlash"))
                .hasSize(2);
    }

    private TrialReport await(Trial trial, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        TrialReport report = reports.report(trial.getId());
        while (!report.finished() && Instant.now().isBefore(deadline)) {
            Thread.sleep(2000);
            report = reports.report(trial.getId());
        }
        return report;
    }
}
