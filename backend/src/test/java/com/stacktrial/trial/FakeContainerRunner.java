package com.stacktrial.trial;

import com.stacktrial.execution.ContainerRunResult;
import com.stacktrial.execution.ContainerRunSpec;
import com.stacktrial.execution.ContainerRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test double for Docker only. It writes the files a real Maven run would leave behind, so everything after the
 * container (parsing, comparison, persistence, explanation, report) runs for real. Used only in tests that must not
 * depend on Docker; the e2e test uses the real DockerContainerRunner.
 */
class FakeContainerRunner implements ContainerRunner {

    private static final String PASSING = """
            <testsuite><testcase classname="com.example.orders.OrderControllerTest" name="listsOrders" time="0.1"/>
            <testcase classname="com.example.orders.OrderControllerTest" name="listsOrdersWithTrailingSlash" time="0.1"/></testsuite>
            """;
    private static final String FAILING = """
            <testsuite><testcase classname="com.example.orders.OrderControllerTest" name="listsOrders" time="0.1"/>
            <testcase classname="com.example.orders.OrderControllerTest" name="listsOrdersWithTrailingSlash" time="0.1">
            <failure message="Status expected:&lt;200&gt; but was:&lt;404&gt;" type="java.lang.AssertionError">java.lang.AssertionError: Status expected:&lt;200&gt; but was:&lt;404&gt;</failure>
            </testcase></testsuite>
            """;

    @Override
    public ContainerRunResult run(ContainerRunSpec spec) {
        try {
            boolean upgraded = Files.readString(spec.workspace().resolve("pom.xml")).contains("<version>3.0.13</version>");
            Path out = spec.collectInto().resolve(".stacktrial");
            Files.createDirectories(out.resolve("surefire-reports"));
            Files.writeString(out.resolve("surefire-reports/TEST-com.example.orders.OrderControllerTest.xml"), upgraded ? FAILING : PASSING);
            Files.writeString(out.resolve("build.log"), upgraded
                    ? "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:2.22.2:test (default-test) on project orders-service: There are test failures.\n"
                    : "[INFO] BUILD SUCCESS\n");
            Files.writeString(out.resolve("dependency-tree.txt"), upgraded
                    ? "com.example:orders-service:jar:1.0.0\n+- org.springframework:spring-webmvc:jar:6.0.14:compile\n"
                    : "com.example:orders-service:jar:1.0.0\n+- org.springframework:spring-webmvc:jar:5.3.31:compile\n");
            return new ContainerRunResult(upgraded ? 1 : 0, false, 1234, "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
