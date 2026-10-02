package com.stacktrial.execution;

import com.stacktrial.evidence.RawRunArtifacts;

/**
 * @param buildLogKey          storage key of the redacted Maven build log
 * @param dependencyTreeKey    storage key of the dependency tree, or null if Maven produced none
 */
public record MavenRunResult(String image, String command, RawRunArtifacts artifacts,
                             String buildLogKey, String dependencyTreeKey) {
}
