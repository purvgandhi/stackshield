package com.stacktrial.workspace.change;

import com.stacktrial.workspace.GitClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeApplierTest {

    static final String POM = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.7.18</version>
                </parent>
                <artifactId>shop</artifactId>
                <properties>
                    <java.version>17</java.version>
                    <commons-lang3.version>3.13.0</commons-lang3.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>${commons-lang3.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>32.1.3-jre</version>
                    </dependency>
                    <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """;

    @TempDir
    Path ws;

    private String pom() throws IOException {
        return Files.readString(ws.resolve("pom.xml"));
    }

    private void writePom(String content) throws IOException {
        Files.writeString(ws.resolve("pom.xml"), content);
    }

    @Test
    void springBootUpgradeChangesOnlyTheParentVersion() throws IOException {
        writePom(POM);
        String summary = new SpringBootVersionChangeApplier()
                .apply(ws, new ChangeRequest(ChangeType.SPRING_BOOT_VERSION, null, null, "3.0.13", null));

        assertThat(summary).contains("2.7.18 -> 3.0.13");
        assertThat(pom()).isEqualTo(POM.replace("<version>2.7.18</version>", "<version>3.0.13</version>"));
    }

    @Test
    void springBootUpgradeRejectsSameVersion() throws IOException {
        writePom(POM);
        assertThatThrownBy(() -> new SpringBootVersionChangeApplier()
                .apply(ws, new ChangeRequest(ChangeType.SPRING_BOOT_VERSION, null, null, "2.7.18", null)))
                .isInstanceOf(ChangeApplicationException.class);
    }

    @Test
    void dependencyWithPropertyVersionUpdatesTheProperty() throws IOException {
        writePom(POM);
        new DependencyVersionChangeApplier().apply(ws,
                new ChangeRequest(ChangeType.DEPENDENCY_VERSION, "org.apache.commons", "commons-lang3", "3.17.0", null));

        assertThat(pom()).contains("<commons-lang3.version>3.17.0</commons-lang3.version>")
                .contains("<version>${commons-lang3.version}</version>");
    }

    @Test
    void dependencyWithLiteralVersionIsReplaced() throws IOException {
        writePom(POM);
        new DependencyVersionChangeApplier().apply(ws,
                new ChangeRequest(ChangeType.DEPENDENCY_VERSION, "com.google.guava", "guava", "33.0.0-jre", null));

        assertThat(pom()).contains("<version>33.0.0-jre</version>").doesNotContain("32.1.3-jre");
    }

    @Test
    void managedDependencyGetsAnExplicitVersion() throws IOException {
        writePom(POM);
        new DependencyVersionChangeApplier().apply(ws,
                new ChangeRequest(ChangeType.DEPENDENCY_VERSION, "com.fasterxml.jackson.core", "jackson-databind", "2.17.0", null));

        assertThat(pom()).contains("<artifactId>jackson-databind</artifactId>\n            <version>2.17.0</version>");
    }

    @Test
    void undeclaredDependencyIsRejected() throws IOException {
        writePom(POM);
        assertThatThrownBy(() -> new DependencyVersionChangeApplier().apply(ws,
                new ChangeRequest(ChangeType.DEPENDENCY_VERSION, "org.acme", "missing", "1.0", null)))
                .isInstanceOf(ChangeApplicationException.class)
                .hasMessageContaining("not declared");
    }

    @Test
    void versionsAreValidatedToKeepThePomWellFormed() throws IOException {
        writePom(POM);
        assertThatThrownBy(() -> new DependencyVersionChangeApplier().apply(ws,
                new ChangeRequest(ChangeType.DEPENDENCY_VERSION, "com.google.guava", "guava", "1.0</version><x>", null)))
                .isInstanceOf(ChangeApplicationException.class);
    }

    @Test
    void javaUpgradeChangesTheJavaVersionProperty() throws IOException {
        writePom(POM);
        new JavaVersionChangeApplier().apply(ws, new ChangeRequest(ChangeType.JAVA_VERSION, null, null, "21", null));
        assertThat(pom()).contains("<java.version>21</java.version>");
    }

    @Test
    void gitDiffIsAppliedWithGitApply() throws Exception {
        writePom(POM);
        Files.writeString(ws.resolve("Hello.java"), "class Hello {\n    String greet() { return \"hi\"; }\n}\n");
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "base");

        String diff = """
                diff --git a/Hello.java b/Hello.java
                --- a/Hello.java
                +++ b/Hello.java
                @@ -1,3 +1,3 @@
                 class Hello {
                -    String greet() { return "hi"; }
                +    String greet() { return "hello"; }
                 }
                """;
        GitClient gitClient = new GitClient();
        String summary = new GitDiffChangeApplier(gitClient).apply(ws, new ChangeRequest(ChangeType.GIT_DIFF, null, null, null, diff));

        assertThat(summary).contains("1 file");
        assertThat(Files.readString(ws.resolve("Hello.java"))).contains("\"hello\"");
        assertThat(gitClient.diffAgainstHead(ws)).contains("+    String greet() { return \"hello\"; }");
    }

    @Test
    void diffThatDoesNotApplyIsRejected() throws Exception {
        writePom(POM);
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "base");
        String diff = """
                diff --git a/Nope.java b/Nope.java
                --- a/Nope.java
                +++ b/Nope.java
                @@ -1 +1 @@
                -old
                +new
                """;
        assertThatThrownBy(() -> new GitDiffChangeApplier(new GitClient())
                .apply(ws, new ChangeRequest(ChangeType.GIT_DIFF, null, null, null, diff)))
                .isInstanceOf(ChangeApplicationException.class)
                .hasMessageContaining("does not apply");
    }

    @Test
    void changeServiceRoutesByType() throws IOException {
        writePom(POM);
        ChangeService service = new ChangeService(List.of(new SpringBootVersionChangeApplier(), new JavaVersionChangeApplier()));
        assertThat(service.apply(ws, new ChangeRequest(ChangeType.JAVA_VERSION, null, null, "21", null))).contains("17 -> 21");
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(ws.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isZero();
    }
}
