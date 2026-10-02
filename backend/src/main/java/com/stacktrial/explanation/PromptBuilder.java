package com.stacktrial.explanation;

import com.stacktrial.comparison.EvidenceItem;
import com.stacktrial.comparison.EvidenceKind;
import com.stacktrial.comparison.Finding;

import java.util.List;

/** Builds the grounded prompt. The verdict is stated as final; the model may only explain it. */
final class PromptBuilder {

    static final String NO_SOURCE = "No relevant source was found.";

    static final String SYSTEM_INSTRUCTION = """
            You explain the results of StackTrial, a tool that ran a Java/Spring Boot Maven project twice in Docker:
            BASELINE (unchanged) and TRIAL (with a proposed change), and compared them with deterministic rules.

            Rules you must follow:
            1. The VERDICT is final. It was computed by deterministic code. Never change it, question it, or offer a different one.
            2. Use only the FINDINGS, EVIDENCE and KNOWLEDGE given below. Do not use outside knowledge about migration
               guidance. If the knowledge does not cover the cause, write exactly: "%s"
            3. Every factual sentence must cite at least one id in square brackets, e.g. [EVIDENCE-003] or [KB-SB30-02].
               Only cite ids that appear below.
            4. Do not write code and do not propose code changes. Explain causes only.
            5. If the evidence is not enough to know the cause, say so plainly.

            Answer in plain text (no Markdown) with these sections, each heading in capitals on its own line:
            SUMMARY
            WHY IT HAPPENED
            RELEVANT DOCUMENTATION
            GAPS
            """.formatted(NO_SOURCE);

    private static final int MAX_EVIDENCE_DETAIL = 1200;

    private PromptBuilder() {
    }

    static String build(ExplanationInput input, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Explain why these actual findings occurred.\n\n");
        sb.append("PROPOSED CHANGE\n").append(input.changeSummary()).append("\n\n");
        sb.append("VERDICT (final, computed deterministically)\n")
                .append(input.verdict()).append(": ").append(input.verdictReason()).append("\n\n");

        sb.append("FINDINGS\n");
        for (Finding f : input.findings()) {
            sb.append("- [").append(f.severity()).append("] ").append(f.type()).append(": ").append(f.title())
                    .append(". ").append(nullToEmpty(f.description()))
                    .append(" Baseline evidence: ").append(f.baselineEvidence())
                    .append(". Trial evidence: ").append(f.trialEvidence()).append("\n");
        }

        sb.append("\nEVIDENCE\n");
        for (EvidenceItem e : input.evidence()) {
            sb.append("[").append(e.code()).append("] ").append(e.title()).append("\n")
                    .append(indent(truncate(nullToEmpty(e.detail())))).append("\n");
        }

        sb.append("\nKNOWLEDGE\n");
        if (chunks.isEmpty()) {
            sb.append("(no relevant knowledge was retrieved)\n");
        }
        for (RetrievedChunk r : chunks) {
            KnowledgeChunk c = r.chunk();
            sb.append("[").append(c.id()).append("] ").append(c.sourceTitle()).append(" / ").append(c.title()).append("\n")
                    .append(indent(c.content())).append("\n");
        }
        return sb.toString();
    }

    /**
     * The text used to search the knowledge base: what changed and what broke. Only regressions and failure details
     * are used; run summaries and dependency lists would drown the signal.
     */
    static String retrievalQuery(ExplanationInput input) {
        StringBuilder sb = new StringBuilder(input.changeSummary()).append('\n');
        input.findings().stream()
                .filter(f -> f.severity() != com.stacktrial.comparison.Severity.INFO)
                .forEach(f -> sb.append(f.title()).append('\n'));
        input.evidence().stream()
                .filter(e -> e.kind() == EvidenceKind.TEST_FAILURE || e.kind() == EvidenceKind.COMPILE_ERROR)
                .forEach(e -> sb.append(firstLines(nullToEmpty(e.detail()), 2)).append('\n'));
        return sb.length() > 4000 ? sb.substring(0, 4000) : sb.toString();
    }

    private static String firstLines(String s, int n) {
        return String.join("\n", s.lines().limit(n).toList());
    }

    private static String indent(String s) {
        return "    " + s.replace("\n", "\n    ");
    }

    private static String truncate(String s) {
        return s.length() <= MAX_EVIDENCE_DETAIL ? s : s.substring(0, MAX_EVIDENCE_DETAIL) + " ...";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
