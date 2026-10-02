package com.stacktrial.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PomInspectorTest {

    @Test
    void detectsBootAndJavaVersions(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.2.12</version></parent>
                  <groupId>com.acme</groupId><artifactId>shop</artifactId>
                  <properties><java.version>17</java.version></properties>
                </project>
                """);
        ProjectInfo info = new PomInspector().inspect(ws);
        assertThat(info.springBootVersion()).isEqualTo("3.2.12");
        assertThat(info.javaVersion()).isEqualTo(17);
        assertThat(info.artifactId()).isEqualTo("shop");
    }

    @Test
    void parsesLegacyJavaVersionNotation() {
        assertThat(PomInspector.parseJavaMajor("1.8")).isEqualTo(8);
        assertThat(PomInspector.parseJavaMajor("21")).isEqualTo(21);
        assertThat(PomInspector.parseJavaMajor("${jdk}")).isNull();
    }

    @Test
    void rejectsNonMavenRepositories(@TempDir Path ws) {
        assertThatThrownBy(() -> new PomInspector().inspect(ws))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("Maven");
    }
}
