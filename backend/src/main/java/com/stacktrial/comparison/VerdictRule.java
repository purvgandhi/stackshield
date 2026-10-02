package com.stacktrial.comparison;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One row of the verdict decision table. Rules are evaluated top to bottom; the first match wins.
 */
record VerdictRule(String name, Predicate<ComparisonContext> when, Verdict verdict,
                   Function<ComparisonContext, String> reason) {
}
