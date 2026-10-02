package com.stacktrial.execution;

import com.stacktrial.common.StackTrialProperties;
import com.stacktrial.workspace.UnsupportedProjectException;
import org.springframework.stereotype.Component;

/**
 * Picks the runner image from the Java version declared in a workspace's pom. Baseline and trial each use their own
 * pom, so a Java upgrade trial automatically runs on the newer JDK while everything else stays identical.
 */
@Component
public class RunnerImageResolver {

    private final StackTrialProperties.Runner config;

    public RunnerImageResolver(StackTrialProperties properties) {
        this.config = properties.runner();
    }

    public String imageFor(Integer javaVersion) {
        int version = javaVersion == null ? config.defaultJavaVersion() : javaVersion;
        String image = config.images().get(version);
        if (image == null) {
            throw new UnsupportedProjectException("No runner image configured for Java " + version
                    + ". Configured: " + config.images().keySet());
        }
        return image;
    }
}
