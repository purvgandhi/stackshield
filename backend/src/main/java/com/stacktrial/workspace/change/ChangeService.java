package com.stacktrial.workspace.change;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routes a {@link ChangeRequest} to the {@link ChangeApplier} for its type. */
@Service
public class ChangeService {

    private final Map<ChangeType, ChangeApplier> appliers = new EnumMap<>(ChangeType.class);

    public ChangeService(List<ChangeApplier> appliers) {
        appliers.forEach(a -> this.appliers.put(a.type(), a));
    }

    public String apply(Path trialWorkspace, ChangeRequest change) {
        ChangeApplier applier = appliers.get(change.type());
        if (applier == null) {
            throw new ChangeApplicationException("NOT IMPLEMENTED: change type " + change.type());
        }
        return applier.apply(trialWorkspace, change);
    }
}
