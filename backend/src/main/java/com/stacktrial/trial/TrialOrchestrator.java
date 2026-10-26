package com.stacktrial.trial;

import com.stacktrial.common.StackTrialProperties;
import com.stacktrial.comparison.ComparisonEngine;
import com.stacktrial.comparison.ComparisonResult;
import com.stacktrial.comparison.Side;
import com.stacktrial.evidence.EvidenceCollector;
import com.stacktrial.evidence.RunEvidence;
import com.stacktrial.execution.ContainerExecutionException;
import com.stacktrial.execution.MavenRunExecutor;
import com.stacktrial.execution.MavenRunResult;
import com.stacktrial.execution.RunnerImageResolver;
import com.stacktrial.explanation.ExplanationInput;
import com.stacktrial.explanation.ExplanationService;
import com.stacktrial.workspace.GitClient;
import com.stacktrial.workspace.GitCommandException;
import com.stacktrial.workspace.PomInspector;
import com.stacktrial.workspace.PreparedWorkspaces;
import com.stacktrial.workspace.ProjectInfo;
import com.stacktrial.workspace.UnsupportedProjectException;
import com.stacktrial.workspace.WorkspaceService;
import com.stacktrial.workspace.change.ChangeApplicationException;
import com.stacktrial.workspace.change.ChangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Runs one trial end to end:
 * <pre>
 * PREPARING  clone, checkout baseline ref, baseline + trial workspaces, apply change to trial
 * RUNNING    baseline container, then trial container (same image rules, limits and command)
 * ANALYZING  parse evidence, deterministic comparison, verdict
 * EXPLAINING retrieve knowledge, Gemini explanation (cannot change the verdict)
 * COMPLETED
 * </pre>
 * Any expected problem ends the trial as FAILED with a readable reason; nothing is left RUNNING.
 */
@Service
public class TrialOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TrialOrchestrator.class);

    private final TrialStore store;
    private final WorkspaceService workspaces;
    private final PomInspector pomInspector;
    private final ChangeService changes;
    private final GitClient git;
    private final RunnerImageResolver images;
    private final MavenRunExecutor maven;
    private final EvidenceCollector evidenceCollector;
    private final ComparisonEngine comparisonEngine;
    private final ExplanationService explanations;
    private final boolean keepWorkspaces;

    public TrialOrchestrator(TrialStore store, WorkspaceService workspaces, PomInspector pomInspector,
                             ChangeService changes, GitClient git, RunnerImageResolver images, MavenRunExecutor maven,
                             EvidenceCollector evidenceCollector, ComparisonEngine comparisonEngine,
                             ExplanationService explanations, StackTrialProperties properties) {
        this.store = store;
        this.workspaces = workspaces;
        this.pomInspector = pomInspector;
        this.changes = changes;
        this.git = git;
        this.images = images;
        this.maven = maven;
        this.evidenceCollector = evidenceCollector;
        this.comparisonEngine = comparisonEngine;
        this.explanations = explanations;
        this.keepWorkspaces = properties.workspace().keepWorkspaces();
    }

    public void execute(UUID trialId) {
        MDC.put("trialId", trialId.toString());
        try {
            Trial trial = store.get(trialId);
            log.info("Trial started: {} on {}@{}", trial.getChangeSummary(), trial.getRepoUrl(), trial.getBaselineRef());

            // ---- PREPARING
            step(trialId, TrialStatus.PREPARING, "Cloning repository");
            PreparedWorkspaces ws = prepare(trialId, trial);
            ProjectInfo baselineInfo = pomInspector.inspect(ws.baselineDir());

            step(trialId, TrialStatus.PREPARING, "Applying change to trial workspace");
            String applied = changes.apply(ws.trialDir(), trial.changeRequest());
            ProjectInfo trialInfo = pomInspector.inspect(ws.trialDir());
            String diff = git.diffAgainstHead(ws.trialDir());
            if (diff.isBlank()) {
                throw new TrialFailureException("The change produced no difference in the repository");
            }
            store.recordPreparation(trialId, ws.commitSha(), trial.changeRequest().describe() + " — " + applied, diff,
                    baselineInfo.javaVersion(), trialInfo.javaVersion(),
                    baselineInfo.springBootVersion(), trialInfo.springBootVersion());

            String baselineImage = images.imageFor(baselineInfo.javaVersion());
            String trialImage = images.imageFor(trialInfo.javaVersion());

            // ---- RUNNING
            step(trialId, TrialStatus.RUNNING, "Running baseline in Docker (" + baselineImage + ")");
            RunEvidence baseline = runSide(trialId, Side.BASELINE, ws, baselineImage);

            step(trialId, TrialStatus.RUNNING, "Running trial in Docker (" + trialImage + ")");
            RunEvidence trialRun = runSide(trialId, Side.TRIAL, ws, trialImage);

            // ---- ANALYZING
            step(trialId, TrialStatus.ANALYZING, "Comparing baseline and trial");
            ComparisonResult comparison = comparisonEngine.compare(baseline, trialRun);
            store.saveComparison(trialId, comparison);
            log.info("Verdict {}: {}", comparison.verdict(), comparison.verdictReason());

            step(trialId, TrialStatus.COMPLETED, "Completed");
            log.info("Trial completed successfully with deterministic verdict: {}", comparison.verdict());
        } catch (TrialFailureException | UnsupportedProjectException | ChangeApplicationException
                 | GitCommandException | ContainerExecutionException | IllegalArgumentException e) {
            log.warn("Trial failed: {}", e.getMessage());
            store.fail(trialId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Trial failed unexpectedly", e);
            store.fail(trialId, "Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            maven.discardCollected(trialId);
            if (!keepWorkspaces) {
                workspaces.cleanup(trialId);
            }
            MDC.clear();
        }
    }

    private PreparedWorkspaces prepare(UUID trialId, Trial trial) {
        try {
            return workspaces.prepare(trialId, trial.getRepoUrl(), trial.getBaselineRef());
        } catch (GitCommandException e) {
            throw new TrialFailureException("Repository unavailable: " + e.getMessage());
        }
    }

    private RunEvidence runSide(UUID trialId, Side side, PreparedWorkspaces ws, String image) {
        MDC.put("step", side.name());
        try {
            MavenRunResult result = maven.run(trialId, side,
                    side == Side.BASELINE ? ws.baselineDir() : ws.trialDir(), image);
            RunEvidence evidence = evidenceCollector.collect(result.artifacts());
            store.saveRun(trialId, side, result, evidence);
            log.info("{} finished: {} in phase {}, tests {}", side, evidence.outcome(), evidence.phaseReached(),
                    evidence.testSummary().describe());
            return evidence;
        } finally {
            MDC.remove("step");
        }
    }

    private void step(UUID trialId, TrialStatus status, String description) {
        MDC.put("step", status.name());
        store.moveTo(trialId, status, description);
    }
}
