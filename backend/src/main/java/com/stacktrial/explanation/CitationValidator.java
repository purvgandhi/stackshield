package com.stacktrial.explanation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks every id the model cited against the evidence and knowledge chunks it was actually given. */
public final class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\b(EVIDENCE-\\d{3}|KB-[A-Z0-9]+-\\d{2})\\b");

    private CitationValidator() {
    }

    public static Citations validate(String text, Set<String> evidenceCodes, Set<String> chunkIds) {
        Set<String> evidence = new LinkedHashSet<>();
        Set<String> knowledge = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = CITATION.matcher(text);
            while (m.find()) {
                String id = m.group(1);
                if (evidenceCodes.contains(id)) {
                    evidence.add(id);
                } else if (chunkIds.contains(id)) {
                    knowledge.add(id);
                } else {
                    unknown.add(id);
                }
            }
        }
        return new Citations(new ArrayList<>(evidence), new ArrayList<>(knowledge), new ArrayList<>(unknown));
    }

    public static Citations none() {
        return new Citations(List.of(), List.of(), List.of());
    }

    /** @param unknown ids that look like citations but were never provided: a sign of hallucination */
    public record Citations(List<String> evidence, List<String> knowledge, List<String> unknown) {
    }
}
