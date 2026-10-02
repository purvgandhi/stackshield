# Comparison engine

`comparison/ComparisonEngine.java` takes the baseline `RunEvidence` and the trial `RunEvidence` and returns a `ComparisonResult`: numbered evidence, findings and **the verdict**. It is pure Java: no Spring annotations, no database, no clock, no randomness, no AI. Same inputs, same output, every time.

```java
ComparisonResult compare(RunEvidence baseline, RunEvidence trial)
    → verdict, verdictReason, List<Finding> findings, List<EvidenceItem> evidence
```

## Step 1: record evidence

`EvidenceLedger` hands out codes in the order facts are recorded: `EVIDENCE-001` is always the baseline run, `EVIDENCE-002` the trial run. Then compile errors, test facts and dependency changes, each in sorted order. Because the order is deterministic, a finding or an AI explanation can cite `[EVIDENCE-004]` and it means the same thing on every re-run.

## Step 2: derive findings

| Finding | When | Severity |
|---|---|---|
| `RUN_TIMEOUT` | a side was killed by the hard timeout | HIGH |
| `BUILD_REGRESSION` | baseline `SUCCESS`, trial `FAILURE` (compile errors attached as evidence) | CRITICAL |
| `BUILD_FIXED` | baseline `FAILURE`, trial `SUCCESS` | INFO |
| `BUILD_FAILED_BOTH` | both `FAILURE` | HIGH |
| `TEST_REGRESSION` | test **passed in baseline** and failed/errored in trial | HIGH |
| `TEST_MISSING` | passed in baseline, absent in trial, **and the trial actually reached the test phase** | MEDIUM |
| `TEST_FIXED` | failed in baseline, passes in trial | INFO |
| `TEST_STILL_FAILING` | failed on both sides (one finding listing them) | LOW |
| `NO_TESTS` | both builds succeeded and neither ran any test | LOW |
| `DEPENDENCY_CHANGED` | resolved versions differ, or artifacts added/removed | INFO |

Tests are matched by `class#name` in a `TreeMap`, so iteration order is sorted and stable.

Two details that prevent false alarms:
- A test failing on **both** sides is `TEST_STILL_FAILING`, not a regression: the change did not cause it.
- If the trial never reached the test phase (for example it failed to compile), missing tests are **not** reported as `TEST_MISSING`; the `BUILD_REGRESSION` already explains them. That is why scenario 4 has exactly one finding.

## Step 3: pick the verdict (decision table)

`VERDICT_RULES` is an ordered list of `VerdictRule(name, predicate, verdict, reason)`. The first rule whose predicate matches wins.

| # | Rule name | Condition | Verdict |
|---|---|---|---|
| 1 | `timeout` | either side timed out | `INCONCLUSIVE` |
| 2 | `both-builds-failed` | both builds failed | `BOTH_FAILED` |
| 3 | `trial-build-broke` | baseline passed, trial failed | `REGRESSION` |
| 4 | `trial-build-fixed` | baseline failed, trial passed | `IMPROVEMENT` |
| 5 | `test-regression` | any `TEST_REGRESSION` | `REGRESSION` |
| 6 | `tests-missing` | any `TEST_MISSING` | `INCONCLUSIVE` |
| 7 | `tests-fixed` | any `TEST_FIXED` | `IMPROVEMENT` |
| 8 | `no-regression` | always | `PASS` |

Why this order:
- **Timeout first**: half a run is not evidence. Calling it a regression would blame the change for an environment problem.
- **Both failed before regression**: if the baseline does not build, there is nothing to compare against.
- **Regression before improvement**: a change that fixes one test and breaks another is a regression.
- **Missing tests before improvement/pass**: a test that silently stopped running is unknown, not a pass.

What is deliberately **not** in the rules:
- **Durations.** Stored and shown as "DURATION (info)" but never used, because timing varies between runs.
- **Dependency changes.** Informational: an upgrade always changes versions; whether it matters shows up in compile and test results.
- **AI.** The explanation step runs after the verdict is saved and has no way to change it.

## Real results

| Scenario | Matching rule | Verdict | Findings |
|---|---|---|---|
| 1 Spring Boot 2.7.18 → 3.0.13 | 3 `trial-build-broke` | `REGRESSION` | BUILD_REGRESSION, 2× TEST_REGRESSION (trailing slash → 404), DEPENDENCY_CHANGED (49) |
| 2 commons-lang3 3.13.0 → 3.17.0 | 8 `no-regression` | `PASS` | DEPENDENCY_CHANGED (1) |
| 3 Java 17 → 21 | 8 `no-regression` | `PASS` | none |
| 4 diff calling a missing method | 3 `trial-build-broke` | `REGRESSION` | BUILD_REGRESSION with the javac error as evidence |
| 5 spring-petclinic (GitHub) Java 17 → 21 | 8 `no-regression` | `PASS` | none (77 passed / 2 skipped on both sides) |

Full JSON: [scenario-results/](scenario-results/).

## Tests
`ComparisonEngineTest` (18 tests): a parameterized test with one case per decision-table row, plus "a new failure needs a baseline pass", "failing on both sides is not a regression", "compile errors become evidence", "dependency diff is informational", "duration never changes the verdict", "same inputs give identical output", and "the table ends with a catch-all PASS rule". The comparison result is never mocked anywhere, including in the integration tests.

## Adding a rule
Add a `FindingType` if needed, derive it in `compare`, insert a `VerdictRule` at the right position, and add a row to the parameterized test. Order is the design: think about which rule should win when two match.
