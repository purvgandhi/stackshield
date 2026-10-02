package com.stacktrial.explanation;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the curated knowledge base from {@code classpath:knowledge/*.md}. File format:
 * <pre>
 * # Document title
 * id: KB-SB30
 * source: https://...
 *
 * ## Section title      -> becomes chunk KB-SB30-01
 * text ...
 * </pre>
 */
@Component
public class KnowledgeLoader {

    public List<KnowledgeChunk> load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md");
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename));
            List<KnowledgeChunk> chunks = new ArrayList<>();
            for (Resource r : resources) {
                chunks.addAll(parse(new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
            }
            return chunks;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load knowledge base", e);
        }
    }

    static List<KnowledgeChunk> parse(String markdown) {
        String docTitle = null, idPrefix = null, source = null;
        List<KnowledgeChunk> chunks = new ArrayList<>();
        String sectionTitle = null;
        StringBuilder body = new StringBuilder();

        for (String line : markdown.split("\\R")) {
            if (line.startsWith("# ") && docTitle == null) {
                docTitle = line.substring(2).strip();
            } else if (line.startsWith("id:") && idPrefix == null) {
                idPrefix = line.substring(3).strip();
            } else if (line.startsWith("source:") && source == null) {
                source = line.substring(7).strip();
            } else if (line.startsWith("## ")) {
                addChunk(chunks, idPrefix, docTitle, source, sectionTitle, body);
                sectionTitle = line.substring(3).strip();
                body.setLength(0);
            } else if (sectionTitle != null) {
                body.append(line).append('\n');
            }
        }
        addChunk(chunks, idPrefix, docTitle, source, sectionTitle, body);
        return chunks;
    }

    private static void addChunk(List<KnowledgeChunk> chunks, String idPrefix, String docTitle, String source,
                                 String sectionTitle, StringBuilder body) {
        if (sectionTitle == null || body.toString().isBlank()) {
            return;
        }
        if (idPrefix == null || docTitle == null) {
            throw new IllegalStateException("Knowledge file needs '# title' and 'id:' before its first section");
        }
        String id = "%s-%02d".formatted(idPrefix, chunks.size() + 1);
        chunks.add(new KnowledgeChunk(id, docTitle, source, sectionTitle, body.toString().strip()));
    }
}
