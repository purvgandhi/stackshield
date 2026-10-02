package com.stacktrial.trial;

import com.stacktrial.workspace.RepositoryUrlValidator;
import com.stacktrial.workspace.change.ChangeRequest;
import com.stacktrial.workspace.change.ChangeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Entry point for creating trials. Validates input, stores the trial as QUEUED and hands it to a worker thread. */
@Service
public class TrialService {

    private static final Logger log = LoggerFactory.getLogger(TrialService.class);

    private final TrialStore store;
    private final TrialRepository trials;
    private final RepositoryUrlValidator urlValidator;
    private final TrialOrchestrator orchestrator;
    private final ThreadPoolTaskExecutor executor;

    public TrialService(TrialStore store, TrialRepository trials, RepositoryUrlValidator urlValidator,
                        TrialOrchestrator orchestrator, ThreadPoolTaskExecutor trialExecutor) {
        this.store = store;
        this.trials = trials;
        this.urlValidator = urlValidator;
        this.orchestrator = orchestrator;
        this.executor = trialExecutor;
    }

    public Trial create(String repoUrl, String baselineRef, ChangeRequest change) {
        urlValidator.validate(repoUrl, baselineRef);
        validateChange(change);

        Trial trial = store.save(Trial.queued(repoUrl.strip(), baselineRef.strip(), change));
        try {
            executor.execute(() -> orchestrator.execute(trial.getId()));
        } catch (TaskRejectedException e) {
            log.warn("Trial queue is full, rejecting {}", trial.getId());
            store.fail(trial.getId(), "Trial queue is full; try again later");
        }
        return trial;
    }

    public List<Trial> recent() {
        return trials.findTop50ByOrderByCreatedAtDesc();
    }

    static void validateChange(ChangeRequest c) {
        if (c == null || c.type() == null) {
            throw new IllegalArgumentException("changeType is required");
        }
        switch (c.type()) {
            case DEPENDENCY_VERSION -> {
                require(c.groupId(), "groupId");
                require(c.artifactId(), "artifactId");
                require(c.targetVersion(), "targetVersion");
            }
            case SPRING_BOOT_VERSION, JAVA_VERSION -> require(c.targetVersion(), "targetVersion");
            case GIT_DIFF -> require(c.diff(), "diff");
        }
        if (c.type() != ChangeType.GIT_DIFF && c.diff() != null && !c.diff().isBlank()) {
            throw new IllegalArgumentException("diff is only allowed for GIT_DIFF changes");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for this change type");
        }
    }
}
