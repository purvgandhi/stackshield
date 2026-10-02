package com.stacktrial.workspace.change;

import java.nio.file.Path;

/** Strategy for one {@link ChangeType}. Applies the change to the trial workspace only. */
public interface ChangeApplier {

    ChangeType type();

    /**
     * @return a one-line, human-readable description of what was actually changed
     * @throws ChangeApplicationException when the change cannot be applied to this project
     */
    String apply(Path workspace, ChangeRequest change);
}
