# Evidence engine

The evidence engine turns the files a container run produced into structured, typed facts (`RunEvidence`). It never decides anything; it only reads. Package: `com.stacktrial.evidence`.

```text
container  /workspace/.stacktrial/
             build.log            ─▶ MavenBuildLogParser   ─▶ phase reached, compile errors, failure summary
             surefire-reports/    ─▶ SurefireReportParser  ─▶ one TestCaseResult per <testcase>
             dependency-tree.txt  ─▶ DependencyTreeParser  ─▶ Dependency (groupId:artifactId → version, scope)
exit code + timed-out flag        ─▶ BuildOutcome SUCCESS | FAILURE | TIMEOUT
                                       │
                         EvidenceCollector.collect(RawRunArtifacts) ─▶ RunEvidence
```

## RunEvidence (one per side)

| Field | Meaning | Source |
|---|---|---|
| `outcome` | `SUCCESS` (exit 0), `FAILURE` (non-zero), `TIMEOUT` (killed) | container exit code / timeout |
| `exitCode` | exit code of `mvn verify` (`-1` on timeout) | container |
| `phaseReached` | `VERIFY` on success; otherwise the phase of the failing plugin: `DEPENDENCY_RESOLUTION`, `COMPILE`, `TEST_COMPILE`, `TEST`, `PACKAGE`, `UNKNOWN` | build log |
| `durationMs` | wall-clock time of the container run (informational only) | runner |
| `tests` | every test case with outcome `PASSED` / `FAILED` / `ERROR` / `SKIPPED`, message, type, stack trace, time | Surefire XML |
| `compileErrors` | file, line, column, message (+ javac `symbol:` / `location:` details) | build log |
| `dependencies` | resolved artifacts with versions and scopes | dependency tree |
| `failureSummary` | the `[ERROR] Failed to execute goal …` line, or a timeout message | build log |

## The parsers

### MavenBuildLogParser
- **Compile errors**: lines like `[ERROR] /workspace/src/main/java/…/GreetingService.java:[11,43] cannot find symbol`. The following indented `symbol:` / `location:` / `required:` / `found:` lines are joined with ` | `, so the evidence reads `cannot find symbol | symbol: method truncateMiddle(java.lang.String,int) | location: class org.apache.commons.lang3.StringUtils`. The `/workspace/` prefix is removed and duplicates (Maven prints each error twice) are dropped; at most a fixed number are kept.
- **Phase**: from the failing plugin in `Failed to execute goal <group>:<plugin>:<version>:<goal>`: compiler → `COMPILE` / `TEST_COMPILE`, surefire/failsafe → `TEST`, jar/spring-boot plugin → `PACKAGE`; "Could not resolve dependencies" → `DEPENDENCY_RESOLUTION`.

### SurefireReportParser
- Reads every `TEST-*.xml` that the container script gathered from all modules' `target/surefire-reports`.
- Each `<testcase classname name time>` becomes a `TestCaseResult` with key `classname#name`; a `<failure>` child means `FAILED`, `<error>` means `ERROR`, `<skipped>` means `SKIPPED`.
- The XML comes from an untrusted build, so DTDs and external entities are disabled (XXE protection). A malformed file raises `EvidenceParsingException` rather than silently producing zero tests.

### DependencyTreeParser
- Parses `mvn dependency:tree -DoutputFile=…` text: strips the tree decoration (`+-`, `|`, `\-`) and parses `groupId:artifactId:type[:classifier]:version[:scope]`. The first line (the project itself) is skipped.

## Example: scenario 4 (compile-breaking diff)

From [scenario-results/4-broken-code-change/report.json](scenario-results/4-broken-code-change/report.json):

```text
baseline  SUCCESS  exit 0  phase VERIFY   tests 5 passed           compile errors 0
trial     FAILURE  exit 1  phase COMPILE  tests none (never ran)   compile errors 1
          GreetingService.java:[11,43] cannot find symbol | symbol: method truncateMiddle(java.lang.String,int)
          | location: class org.apache.commons.lang3.StringUtils
```

## Redaction
Before a build log is stored or used in a prompt, `common/LogRedactor` masks GitHub / Google / AWS / Slack token formats, private-key blocks, bearer tokens, credentials in URLs and `password=` / `api_key:`-style pairs. Redaction runs in `MavenRunExecutor` (storage) and again in `ExplanationService` (prompt).

## Tests
`SurefireReportParserTest`, `MavenBuildLogParserTest`, `DependencyTreeParserTest` use real report and log formats: javac detail lines and deduplication, test / test-compile / dependency-resolution failures, classifiers in the tree, and rejection of an XML report with a DOCTYPE (XXE). `RealDockerE2ETest` and `DockerContainerRunnerIT` run against real containers.
