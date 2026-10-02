package com.stacktrial.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogRedactorTest {

    private final LogRedactor redactor = new LogRedactor();

    @Test
    void masksKnownTokenFormats() {
        String log = "token ghp_abcdefghijklmnopqrstuvwxyz0123456789 key AIzaSyA1234567890abcdefghijklmnopqrstuv";
        String out = redactor.redact(log);
        assertThat(out).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz0123456789").doesNotContain("AIzaSyA1234567890");
        assertThat(out).contains(LogRedactor.MASK);
    }

    @Test
    void masksSecretAssignmentsAndUrlCredentials() {
        String out = redactor.redact("spring.datasource.password=s3cr3t\nAPI_KEY: abcdef123\nclone https://bob:hunter2@github.com/x");
        assertThat(out).doesNotContain("s3cr3t").doesNotContain("abcdef123").doesNotContain("hunter2");
        assertThat(out).contains("spring.datasource.password=" + LogRedactor.MASK);
    }

    @Test
    void leavesOrdinaryBuildOutputAlone() {
        String line = "[INFO] Tests run: 8, Failures: 2, Errors: 0, Skipped: 0";
        assertThat(redactor.redact(line)).isEqualTo(line);
    }
}
