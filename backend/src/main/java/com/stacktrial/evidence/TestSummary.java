package com.stacktrial.evidence;

import java.util.List;

public record TestSummary(int total, int passed, int failed, int errors, int skipped) {

    public static TestSummary of(List<TestCaseResult> tests) {
        int passed = 0, failed = 0, errors = 0, skipped = 0;
        for (TestCaseResult t : tests) {
            switch (t.outcome()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case ERROR -> errors++;
                case SKIPPED -> skipped++;
            }
        }
        return new TestSummary(tests.size(), passed, failed, errors, skipped);
    }

    public String describe() {
        if (total == 0) {
            return "no tests ran";
        }
        StringBuilder sb = new StringBuilder(passed + " passed");
        if (failed > 0) sb.append(" / ").append(failed).append(" failed");
        if (errors > 0) sb.append(" / ").append(errors).append(" errors");
        if (skipped > 0) sb.append(" / ").append(skipped).append(" skipped");
        return sb.toString();
    }
}
