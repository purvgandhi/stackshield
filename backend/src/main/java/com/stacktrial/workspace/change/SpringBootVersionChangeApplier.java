package com.stacktrial.workspace.change;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class SpringBootVersionChangeApplier implements ChangeApplier {

    @Override
    public ChangeType type() {
        return ChangeType.SPRING_BOOT_VERSION;
    }

    @Override
    public String apply(Path workspace, ChangeRequest change) {
        PomEditor.requireVersion(change.targetVersion());
        PomEditor pom = new PomEditor(workspace);
        String old = pom.setSpringBootParentVersion(change.targetVersion());
        String where = "spring-boot-starter-parent";
        if (old == null) {
            old = pom.setProperty("spring-boot.version", change.targetVersion());
            where = "property spring-boot.version";
        }
        if (old == null) {
            throw new ChangeApplicationException(
                    "Spring Boot version not found: expected spring-boot-starter-parent or a spring-boot.version property");
        }
        if (old.equals(change.targetVersion())) {
            throw new ChangeApplicationException("Project already uses Spring Boot " + old);
        }
        pom.save();
        return "Spring Boot " + old + " -> " + change.targetVersion() + " (" + where + ")";
    }
}
