package com.stacktrial.workspace.change;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Changes the Java version declared in the pom. The runner image follows automatically, because the image for
 * each side is chosen from that side's own pom (see {@code RunnerImageResolver}).
 */
@Component
public class JavaVersionChangeApplier implements ChangeApplier {

    private static final List<String> PROPERTIES =
            List.of("java.version", "maven.compiler.release", "maven.compiler.source", "maven.compiler.target");

    @Override
    public ChangeType type() {
        return ChangeType.JAVA_VERSION;
    }

    @Override
    public String apply(Path workspace, ChangeRequest change) {
        String target = change.targetVersion() == null ? null : change.targetVersion().strip();
        if (target == null || !target.matches("\\d{1,2}")) {
            throw new ChangeApplicationException("Java version must be a major version number such as 21");
        }
        PomEditor pom = new PomEditor(workspace);
        List<String> edits = new ArrayList<>();
        for (String property : PROPERTIES) {
            String old = pom.setProperty(property, target);
            if (old != null) {
                edits.add(property + " " + old + " -> " + target);
            }
        }
        if (edits.isEmpty()) {
            throw new ChangeApplicationException("No Java version property (java.version, maven.compiler.release, "
                    + "maven.compiler.source/target) found in pom.xml");
        }
        pom.save();
        return "Java " + String.join(", ", edits);
    }
}
