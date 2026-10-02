package com.stacktrial.trial;

import com.stacktrial.common.NotFoundException;
import com.stacktrial.comparison.Side;
import com.stacktrial.evidence.TestOutcome;
import com.stacktrial.execution.StorageService;
import com.stacktrial.explanation.Explanation;
import com.stacktrial.explanation.ExplanationService;
import com.stacktrial.explanation.KnowledgeChunk;
import com.stacktrial.explanation.KnowledgeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TrialReportService {

    private final TrialStore store;
    private final TrialRunRepository runs;
    private final TestResultRepository testResults;
    private final EvidenceRecordRepository evidence;
    private final FindingRecordRepository findings;
    private final ExplanationService explanations;
    private final KnowledgeRepository knowledge;
    private final StorageService storage;

    public TrialReportService(TrialStore store, TrialRunRepository runs, TestResultRepository testResults,
                              EvidenceRecordRepository evidence, FindingRecordRepository findings,
                              ExplanationService explanations, KnowledgeRepository knowledge, StorageService storage) {
        this.store = store;
        this.runs = runs;
        this.testResults = testResults;
        this.evidence = evidence;
        this.findings = findings;
        this.explanations = explanations;
        this.knowledge = knowledge;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public TrialReport report(UUID id) {
        Trial t = store.get(id);
        Map<Side, TrialRun> bySide = runs.findByTrialId(id).stream()
                .collect(Collectors.toMap(TrialRun::getSide, Function.identity()));
        TrialRun baseline = bySide.get(Side.BASELINE);
        TrialRun trial = bySide.get(Side.TRIAL);

        return new TrialReport(
                t.getId(), t.getRepoUrl(), t.getBaselineRef(), t.getBaselineCommit(), t.getChangeType().name(),
                t.getChangeSummary(), t.getAppliedDiff(), t.getStatus().name(), t.getCurrentStep(),
                t.getStatus().isTerminal(), t.getFailureReason(), t.getVerdict(), t.getVerdictReason(),
                t.getCreatedAt(), t.getStartedAt(), t.getFinishedAt(),
                new TrialReport.Environment(t.getBaselineJava(), t.getTrialJava(), t.getBaselineBoot(), t.getTrialBoot()),
                view(baseline), view(trial),
                comparisonRows(t, baseline, trial),
                findings.findByTrialIdOrderByPositionAsc(id).stream().map(FindingRecord::toFinding).toList(),
                evidence.findByTrialIdOrderByCodeAsc(id).stream().map(EvidenceRecord::toItem).toList(),
                failedTests(baseline, trial),
                explanations.find(id).map(this::explanationView).orElse(null));
    }

    public Optional<String> artifact(UUID trialId, Side side, String kind) {
        TrialRun run = runs.findByTrialIdAndSide(trialId, side)
                .orElseThrow(() -> new NotFoundException("No " + side + " run for trial " + trialId));
        String key = switch (kind) {
            case "log" -> run.getBuildLogKey();
            case "dependency-tree" -> run.getDependencyTreeKey();
            default -> throw new NotFoundException("Unknown artifact " + kind);
        };
        return key == null ? Optional.empty() : storage.readText(key);
    }

    private TrialReport.RunView view(TrialRun r) {
        if (r == null) {
            return null;
        }
        String base = "/api/trials/" + r.getTrialId() + "/runs/" + r.getSide();
        return new TrialReport.RunView(r.getSide().name(), r.getImage(), r.getOutcome().name(), r.getExitCode(),
                r.isTimedOut(), r.getPhaseReached().name(), r.getDurationMs(), r.testSummary(),
                r.getCompileErrorCount(), r.getDependencyCount(), r.getFailureSummary(),
                r.getBuildLogKey() == null ? null : base + "/log",
                r.getDependencyTreeKey() == null ? null : base + "/dependency-tree");
    }

    private List<TrialReport.ComparisonRow> comparisonRows(Trial t, TrialRun b, TrialRun tr) {
        List<TrialReport.ComparisonRow> rows = new ArrayList<>();
        rows.add(row("JAVA", str(t.getBaselineJava()), str(t.getTrialJava())));
        rows.add(row("SPRING BOOT", str(t.getBaselineBoot()), str(t.getTrialBoot())));
        if (b == null && tr == null) {
            return rows;
        }
        rows.add(row("BUILD", b == null ? "not run" : buildLabel(b), tr == null ? "not run" : buildLabel(tr)));
        rows.add(row("PHASE REACHED", b == null ? "-" : b.getPhaseReached().name(), tr == null ? "-" : tr.getPhaseReached().name()));
        rows.add(row("TESTS", b == null ? "-" : b.testSummary().describe(), tr == null ? "-" : tr.testSummary().describe()));
        rows.add(row("COMPILE ERRORS", b == null ? "-" : str(b.getCompileErrorCount()), tr == null ? "-" : str(tr.getCompileErrorCount())));
        rows.add(row("DEPENDENCIES", b == null ? "-" : str(b.getDependencyCount()), tr == null ? "-" : str(tr.getDependencyCount())));
        rows.add(row("EXIT CODE", b == null ? "-" : str(b.getExitCode()), tr == null ? "-" : str(tr.getExitCode())));
        // Duration is informational only: it never affects the verdict, so it is not highlighted as a change.
        rows.add(new TrialReport.ComparisonRow("DURATION (info)", b == null ? "-" : seconds(b.getDurationMs()),
                tr == null ? "-" : seconds(tr.getDurationMs()), false));
        return rows;
    }

    private List<TrialReport.FailedTest> failedTests(TrialRun baseline, TrialRun trial) {
        List<TrialReport.FailedTest> result = new ArrayList<>();
        for (TrialRun run : new TrialRun[]{baseline, trial}) {
            if (run == null) continue;
            testResults.findByRunIdAndOutcomeInOrderByClassNameAscTestNameAsc(run.getId(),
                            EnumSet.of(TestOutcome.FAILED, TestOutcome.ERROR))
                    .forEach(tr -> result.add(new TrialReport.FailedTest(run.getSide().name(),
                            tr.getClassName() + "#" + tr.getTestName(), tr.getOutcome().name(), tr.getFailureType(),
                            tr.getFailureMessage(), tr.getStackTrace())));
        }
        return result;
    }

    private TrialReport.ExplanationView explanationView(Explanation e) {
        List<String> cited = e.getCitedKnowledge();
        List<String> order = e.getRetrievedChunks(); // retrieval rank order, best first
        List<TrialReport.KnowledgeRef> docs = knowledge.findByIds(order).stream()
                .sorted(java.util.Comparator.comparingInt((KnowledgeChunk c) -> order.indexOf(c.id())))
                .map((KnowledgeChunk c) -> new TrialReport.KnowledgeRef(c.id(), c.sourceTitle(), c.sourceUrl(), c.title(),
                        c.content(), cited.contains(c.id())))
                .toList();
        return new TrialReport.ExplanationView(e.getStatus().name(), e.getModel(),
                e.getRetrievalMethod() == null ? null : e.getRetrievalMethod().name(), e.getContent(), e.getStatusReason(),
                e.getCitedEvidence(), cited, e.getUnknownCitations(), docs);
    }

    private static String buildLabel(TrialRun r) {
        return switch (r.getOutcome()) {
            case SUCCESS -> "PASS";
            case FAILURE -> "FAIL";
            case TIMEOUT -> "TIMEOUT";
        };
    }

    private static TrialReport.ComparisonRow row(String label, String baseline, String trial) {
        return new TrialReport.ComparisonRow(label, baseline, trial, !Objects.equals(baseline, trial));
    }

    private static String str(Object o) {
        return o == null ? "unknown" : o.toString();
    }

    private static String seconds(long ms) {
        return String.format("%.1f s", ms / 1000.0);
    }
}
