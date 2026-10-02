package com.stacktrial.workspace.change;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class DependencyVersionChangeApplier implements ChangeApplier {

    @Override
    public ChangeType type() {
        return ChangeType.DEPENDENCY_VERSION;
    }

    @Override
    public String apply(Path workspace, ChangeRequest change) {
        if (isBlank(change.groupId()) || isBlank(change.artifactId())) {
            throw new ChangeApplicationException("groupId and artifactId are required");
        }
        PomEditor.requireVersion(change.targetVersion());
        PomEditor pom = new PomEditor(workspace);
        String edit = pom.setDependencyVersion(change.groupId().strip(), change.artifactId().strip(), change.targetVersion());
        pom.save();
        return change.groupId() + ":" + change.artifactId() + " (" + edit + ")";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
