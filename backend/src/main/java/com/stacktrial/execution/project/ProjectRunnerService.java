package com.stacktrial.execution.project;

import com.stacktrial.execution.ContainerRunResult;
import com.stacktrial.execution.ContainerRunSpec;
import com.stacktrial.execution.ContainerRunner;
import com.stacktrial.execution.RunnerImageResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectRunnerService {

    private static final Logger log = LoggerFactory.getLogger(ProjectRunnerService.class);

    private final ContainerRunner runner;
    private final RunnerImageResolver imageResolver;
    private final ProjectRunRepository runRepo;

    public ProjectRunnerService(ContainerRunner runner, RunnerImageResolver imageResolver, ProjectRunRepository runRepo) {
        this.runner = runner;
        this.imageResolver = imageResolver;
        this.runRepo = runRepo;
    }

    public ProjectRun executeCommand(UUID repositoryId, Path repoPath, String command, String customImage, Integer javaVersion) {
        String image = (customImage != null && !customImage.isBlank())
                ? customImage.trim()
                : imageResolver.imageFor(javaVersion);

        List<String> commandList;
        if (command == null || command.isBlank()) {
            commandList = List.of("mvn", "test");
        } else {
            commandList = Arrays.asList(command.trim().split("\\s+"));
        }

        String containerName = "stacktrial-run-" + UUID.randomUUID().toString().substring(0, 8);
        ContainerRunSpec spec = new ContainerRunSpec(
                containerName,
                image,
                commandList,
                repoPath,
                Map.of(),
                Map.of("stacktrial", "project-run", "repository-id", repositoryId.toString()),
                null,
                null
        );

        log.info("Executing sandbox run for repo {} with command `{}` on image `{}`", repositoryId, String.join(" ", commandList), image);

        ContainerRunResult result;
        ProjectRunOutcome outcome;
        try {
            result = runner.run(spec);
            if (result.timedOut()) {
                outcome = ProjectRunOutcome.TIMED_OUT;
            } else if (result.exitCode() == 0) {
                outcome = ProjectRunOutcome.PASS;
            } else {
                outcome = ProjectRunOutcome.FAIL;
            }
        } catch (Exception e) {
            log.warn("Execution failed in sandbox: {}", e.getMessage());
            result = new ContainerRunResult(-1, false, 0, "Execution failed: " + e.getMessage());
            outcome = ProjectRunOutcome.ERROR;
        }

        ProjectRun run = new ProjectRun(
                UUID.randomUUID(),
                repositoryId,
                String.join(" ", commandList),
                image,
                outcome,
                result.exitCode(),
                result.durationMs(),
                result.consoleLog(),
                Instant.now()
        );

        return runRepo.save(run);
    }

    public List<ProjectRun> getRuns(UUID repositoryId) {
        return runRepo.findByRepositoryIdOrderByCreatedAtDesc(repositoryId);
    }
}
