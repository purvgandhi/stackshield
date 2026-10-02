package com.stacktrial.explanation;

import com.stacktrial.common.LogRedactor;
import com.stacktrial.comparison.EvidenceItem;
import com.stacktrial.comparison.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Step 4 of "evidence first, AI second": retrieve knowledge for the deterministic findings and ask Gemini to explain
 * them with citations. This service can never change the verdict; it only stores an {@link Explanation}.
 * It never throws: any problem becomes an UNAVAILABLE or FAILED explanation and the report still renders.
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    private final KnowledgeRetriever retriever;
    private final ObjectProvider<LlmClient> llm;
    private final ExplanationRepository repository;
    private final LogRedactor redactor;

    public ExplanationService(KnowledgeRetriever retriever, ObjectProvider<LlmClient> llm,
                              ExplanationRepository repository, LogRedactor redactor) {
        this.retriever = retriever;
        this.llm = llm;
        this.repository = repository;
        this.redactor = redactor;
    }

    public Explanation explain(ExplanationInput input) {
        repository.findByTrialId(input.trialId()).ifPresent(repository::delete);

        if (input.verdict() == Verdict.PASS) {
            return repository.save(Explanation.of(input.trialId(), ExplanationStatus.NOT_NEEDED, null, null, null,
                    "Verdict is PASS: no regression to explain.", List.of(), CitationValidator.none()));
        }

        KnowledgeRetriever.Retrieval retrieval;
        try {
            retrieval = retriever.retrieve(redactor.redact(PromptBuilder.retrievalQuery(input)));
        } catch (RuntimeException e) {
            log.warn("Knowledge retrieval failed: {}", e.getMessage());
            retrieval = new KnowledgeRetriever.Retrieval(RetrievalMethod.FULL_TEXT, List.of());
        }
        List<String> retrievedIds = retrieval.chunks().stream().map(r -> r.chunk().id()).toList();

        LlmClient client = llm.getIfAvailable();
        if (client == null) {
            return repository.save(Explanation.of(input.trialId(), ExplanationStatus.UNAVAILABLE, null,
                    retrieval.method(), null, "GEMINI_API_KEY is not configured. The deterministic report is complete; "
                            + "the retrieved documentation below was selected without AI.",
                    retrievedIds, CitationValidator.none()));
        }

        try {
            String prompt = redactor.redact(PromptBuilder.build(redactedInput(input), retrieval.chunks()));
            String answer = client.generate(PromptBuilder.SYSTEM_INSTRUCTION, prompt);
            Set<String> evidenceCodes = input.evidence().stream().map(EvidenceItem::code).collect(Collectors.toSet());
            CitationValidator.Citations citations = CitationValidator.validate(answer, evidenceCodes, Set.copyOf(retrievedIds));
            String note = citations.evidence().isEmpty() && citations.knowledge().isEmpty()
                    ? "Warning: the explanation cites no evidence."
                    : citations.unknown().isEmpty() ? null : "Warning: the explanation cites ids that were not provided: "
                    + String.join(", ", citations.unknown());
            return repository.save(Explanation.of(input.trialId(), ExplanationStatus.GENERATED, client.modelName(),
                    retrieval.method(), answer, note, retrievedIds, citations));
        } catch (RuntimeException e) {
            log.warn("Gemini explanation failed: {}", e.getMessage());
            return repository.save(Explanation.of(input.trialId(), ExplanationStatus.FAILED, client.modelName(),
                    retrieval.method(), null, "Gemini call failed: " + e.getMessage(), retrievedIds,
                    CitationValidator.none()));
        }
    }

    public java.util.Optional<Explanation> find(UUID trialId) {
        return repository.findByTrialId(trialId);
    }

    private ExplanationInput redactedInput(ExplanationInput input) {
        List<EvidenceItem> evidence = input.evidence().stream()
                .map(e -> new EvidenceItem(e.code(), e.kind(), e.side(), redactor.redact(e.title()), redactor.redact(e.detail())))
                .toList();
        return new ExplanationInput(input.trialId(), input.changeSummary(), input.verdict(), input.verdictReason(),
                input.findings(), evidence);
    }
}
