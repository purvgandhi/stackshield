package com.stacktrial.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SurefireReportParserTest {

    static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.acme.OrderControllerTest" tests="4" failures="1" errors="1" skipped="1">
              <testcase name="listsOrders" classname="com.acme.OrderControllerTest" time="0.120"/>
              <testcase name="trailingSlash" classname="com.acme.OrderControllerTest" time="0.015">
                <failure message="Status expected:&lt;200&gt; but was:&lt;404&gt;" type="java.lang.AssertionError">java.lang.AssertionError: Status expected:&lt;200&gt; but was:&lt;404&gt;
                at com.acme.OrderControllerTest.trailingSlash(OrderControllerTest.java:44)</failure>
              </testcase>
              <testcase name="boom" classname="com.acme.OrderControllerTest" time="0.002">
                <error message="NPE" type="java.lang.NullPointerException">java.lang.NullPointerException: NPE</error>
              </testcase>
              <testcase name="later" classname="com.acme.OrderControllerTest" time="0">
                <skipped/>
              </testcase>
            </testsuite>
            """;

    private final SurefireReportParser parser = new SurefireReportParser();

    @Test
    void parsesOutcomesMessagesAndDurations() {
        List<TestCaseResult> results = parser.parse(new ByteArrayInputStream(REPORT.getBytes(StandardCharsets.UTF_8)));

        assertThat(results).extracting(TestCaseResult::outcome)
                .containsExactly(TestOutcome.PASSED, TestOutcome.FAILED, TestOutcome.ERROR, TestOutcome.SKIPPED);
        TestCaseResult failed = results.get(1);
        assertThat(failed.key()).isEqualTo("com.acme.OrderControllerTest#trailingSlash");
        assertThat(failed.failureMessage()).isEqualTo("Status expected:<200> but was:<404>");
        assertThat(failed.failureType()).isEqualTo("java.lang.AssertionError");
        assertThat(failed.stackTrace()).contains("OrderControllerTest.java:44");
        assertThat(results.get(0).durationMs()).isEqualTo(120);
        assertThat(TestSummary.of(results).describe()).isEqualTo("1 passed / 1 failed / 1 errors / 1 skipped");
    }

    @Test
    void readsOnlyTestReportsFromDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("TEST-com.acme.OrderControllerTest.xml"), REPORT);
        Files.writeString(dir.resolve("com.acme.OrderControllerTest.txt"), "plain text summary");

        assertThat(parser.parseDirectory(dir)).hasSize(4);
        assertThat(parser.parseDirectory(dir.resolve("missing"))).isEmpty();
    }

    @Test
    void rejectsXmlWithDoctype() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <testsuite><testcase name="&xxe;" classname="x"/></testsuite>
                """;
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(EvidenceParsingException.class);
    }
}
