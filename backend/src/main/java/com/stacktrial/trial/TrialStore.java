package com.stacktrial.trial;

import com.stacktrial.common.NotFoundException;
import com.stacktrial.comparison.ComparisonResult;
import com.stacktrial.comparison.Side;
import com.stacktrial.evidence.RunEvidence;
import com.stacktrial.execution.MavenRunResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Short transactions used by the long-running orchestrator, so trial state is visible while it runs. */
@Component
public class TrialStore {

    private final TrialRepository trials;
    private final TrialRunRepository runs;
    private final TestResultRepository testResults;
    private final EvidenceRecordRepository evidence;
    private final FindingRecordRepository findings;

    public TrialStore(TrialRepository trials, TrialRunRepository runs, TestResultRepository testResults,
                      EvidenceRecordRepository evidence, FindingRecordRepository findings) {
        this.trials = trials;
        this.runs = runs;
        this.testResults = testResults;
        this.evidence = evidence;
        this.findings = findings;
    }

    @Transactional(readOnly = true)
    public Trial get(UUID id) {
        return trials.findById(id).orElseThrow(() -> new NotFoundException("Trial " + id + " not found"));
    }

    @Transactional
    public Trial save(Trial trial) {
        return trials.save(trial);
    }

    @Transactional
    public void moveTo(UUID id, TrialStatus status, String step) {
        Trial t = get(id);
        t.moveTo(status, step);
        trials.save(t);
    }

    @Transactional
    public void fail(UUID id, String reason) {
        Trial t = get(id);
        if (!t.getStatus().isTerminal()) {
            t.fail(reason);
            trials.save(t);
        }
    }

    @Transactional
    public void recordPreparation(UUID id, String commit, String applied, String diff,
                                  Integer baselineJava, Integer trialJava, String baselineBoot, String trialBoot) {
        Trial t = get(id);
        t.recordPreparation(commit, applied, diff, baselineJava, trialJava, baselineBoot, trialBoot);
        trials.save(t);
    }

    @Transactional
    public TrialRun saveRun(UUID trialId, Side side, MavenRunResult run, RunEvidence runEvidence) {
        TrialRun saved = runs.save(TrialRun.of(trialId, side, run, runEvidence));
        testResults.saveAll(runEvidence.tests().stream().map(t -> TestResult.of(saved.getId(), t)).toList());
        return saved;
    }

    @Transactional
    public void saveComparison(UUID trialId, ComparisonResult result) {
        evidence.saveAll(result.evidence().stream().map(e -> EvidenceRecord.of(trialId, e)).toList());
        List<FindingRecord> records = new java.util.ArrayList<>();
        for (int i = 0; i < result.findings().size(); i++) {
            records.add(FindingRecord.of(trialId, i, result.findings().get(i)));
        }
        findings.saveAll(records);
        Trial t = get(trialId);
        t.recordVerdict(result.verdict(), result.verdictReason());
        trials.save(t);
    }
}
