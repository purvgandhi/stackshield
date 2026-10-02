package com.stacktrial.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MavenBuildLogParserTest {

    private final MavenBuildLogParser parser = new MavenBuildLogParser();

    @Test
    void extractsCompileErrorsAndPhase() {
        String log = """
                [INFO] --- compiler:3.10.1:compile (default-compile) @ shop ---
                [ERROR] COMPILATION ERROR :
                [ERROR] /workspace/src/main/java/com/acme/Shop.java:[3,24] package javax.annotation does not exist
                [ERROR] /workspace/src/main/java/com/acme/Shop.java:[9,6] cannot find symbol
                [INFO] BUILD FAILURE
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.10.1:compile (default-compile) on project shop: Compilation failure
                """;

        var parsed = parser.parse(log, false);

        assertThat(parsed.phaseReached()).isEqualTo(BuildPhase.COMPILE);
        assertThat(parsed.compileErrors()).hasSize(2);
        assertThat(parsed.compileErrors().get(0).file()).isEqualTo("src/main/java/com/acme/Shop.java");
        assertThat(parsed.compileErrors().get(0).line()).isEqualTo(3);
        assertThat(parsed.compileErrors().get(0).message()).isEqualTo("package javax.annotation does not exist");
        assertThat(parsed.failureSummary()).startsWith("Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin");
    }

    @Test
    void attachesJavacSymbolDetailsAndDeduplicates() {
        String log = """
                [ERROR] /workspace/src/main/java/A.java:[11,39] cannot find symbol
                  symbol:   method truncateMiddle(java.lang.String,int)
                  location: class org.apache.commons.lang3.StringUtils
                [INFO] 1 error
                [ERROR] /workspace/src/main/java/A.java:[11,39] cannot find symbol
                [ERROR]   symbol:   method truncateMiddle(java.lang.String,int)
                [ERROR]   location: class org.apache.commons.lang3.StringUtils
                """;
        var errors = parser.parse(log, false).compileErrors();
        assertThat(errors).singleElement().extracting(CompileError::message).isEqualTo(
                "cannot find symbol | symbol: method truncateMiddle(java.lang.String,int) | location: class org.apache.commons.lang3.StringUtils");
    }

    @Test
    void recognisesTestPhaseFailure() {
        String log = "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:2.22.2:test (default-test) on project shop: There are test failures.";
        assertThat(parser.parse(log, false).phaseReached()).isEqualTo(BuildPhase.TEST);
    }

    @Test
    void recognisesTestCompileAndDependencyFailures() {
        assertThat(parser.parse("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project x", false)
                .phaseReached()).isEqualTo(BuildPhase.TEST_COMPILE);
        assertThat(parser.parse("[ERROR] Failed to execute goal on project x: Could not resolve dependencies for project x", false)
                .phaseReached()).isEqualTo(BuildPhase.DEPENDENCY_RESOLUTION);
    }

    @Test
    void successfulBuildReachesVerify() {
        var parsed = parser.parse("[INFO] BUILD SUCCESS", true);
        assertThat(parsed.phaseReached()).isEqualTo(BuildPhase.VERIFY);
        assertThat(parsed.failureSummary()).isNull();
    }
}
