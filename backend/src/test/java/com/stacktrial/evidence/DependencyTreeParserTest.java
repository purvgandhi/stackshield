package com.stacktrial.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyTreeParserTest {

    @Test
    void parsesTreeWithClassifiersAndAnnotations() {
        String tree = """
                com.example:orders-service:jar:1.0.0
                +- org.springframework.boot:spring-boot-starter-web:jar:3.0.13:compile
                |  +- com.fasterxml.jackson.core:jackson-databind:jar:2.14.3:compile
                |  \\- org.springframework:spring-webmvc:jar:6.0.14:compile (version managed from 6.0.13)
                \\- org.junit.jupiter:junit-jupiter-api:jar:tests:5.9.3:test
                """;

        var deps = new DependencyTreeParser().parse(tree);

        assertThat(deps).extracting(Dependency::key).containsExactly(
                "com.fasterxml.jackson.core:jackson-databind",
                "org.junit.jupiter:junit-jupiter-api",
                "org.springframework.boot:spring-boot-starter-web",
                "org.springframework:spring-webmvc");
        assertThat(deps).filteredOn(d -> d.artifactId().equals("spring-webmvc"))
                .singleElement().extracting(Dependency::version).isEqualTo("6.0.14");
        assertThat(deps).filteredOn(d -> d.artifactId().equals("junit-jupiter-api"))
                .singleElement().satisfies(d -> {
                    assertThat(d.version()).isEqualTo("5.9.3");
                    assertThat(d.scope()).isEqualTo("test");
                });
    }

    @Test
    void emptyInputGivesNoDependencies() {
        assertThat(new DependencyTreeParser().parse("")).isEmpty();
        assertThat(new DependencyTreeParser().parse(null)).isEmpty();
    }
}
