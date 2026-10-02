package com.stacktrial.qa;

import com.stacktrial.explanation.EmbeddingClient;
import com.stacktrial.explanation.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RepositoryQaService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryQaService.class);
    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int CHUNK_OVERLAP = 150;
    private static final Pattern CLASS_OR_METHOD = Pattern.compile("(?:public|protected|private|class|interface|record|enum)\\s+([A-Za-z0-9_]+)");

    private final RepositoryChunkRepository chunkRepo;
    private final Optional<LlmClient> llm;
    private final Optional<EmbeddingClient> embeddingClient;

    public RepositoryQaService(RepositoryChunkRepository chunkRepo,
                               Optional<LlmClient> llm,
                               Optional<EmbeddingClient> embeddingClient) {
        this.chunkRepo = chunkRepo;
        this.llm = llm;
        this.embeddingClient = embeddingClient;
    }

    public void indexRepository(UUID repositoryId, Path repoPath) {
        chunkRepo.deleteByRepositoryId(repositoryId);
        List<RepositoryChunk> chunksToIndex = new ArrayList<>();

        try {
            Files.walkFileTree(repoPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("node_modules") || name.equals(".idea") || name.equals("build")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() > 500_000) { // skip large files > 500KB
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString().toLowerCase();
                    if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".py") ||
                            name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".xml") ||
                            name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties") ||
                            name.endsWith(".sql") || name.endsWith(".md") || name.equalsIgnoreCase("dockerfile")) {
                        chunkFile(repositoryId, repoPath, file, chunksToIndex);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error chunking repository at {}: {}", repoPath, e.getMessage());
        }

        log.info("Indexing {} code chunks for repository {}", chunksToIndex.size(), repositoryId);
        for (RepositoryChunk chunk : chunksToIndex) {
            float[] embedding = null;
            if (embeddingClient.isPresent()) {
                try {
                    embedding = embeddingClient.get().embed(chunk.getFilePath() + " " + chunk.getContent(), EmbeddingClient.Purpose.DOCUMENT);
                } catch (Exception e) {
                    log.debug("Failed generating embedding for chunk {}: {}", chunk.getFilePath(), e.getMessage());
                }
            }
            chunkRepo.insertChunk(chunk, embedding);
        }
    }

    public QaResponse answerQuestion(UUID repositoryId, String question) {
        if (question == null || question.isBlank()) {
            return new QaResponse(question, "Please enter a valid question about the codebase.", List.of(), false);
        }

        Set<QaCitation> combinedCitations = new LinkedHashSet<>();

        // 1. Vector Search if embedding is active
        if (embeddingClient.isPresent()) {
            try {
                float[] queryVector = embeddingClient.get().embed(question, EmbeddingClient.Purpose.QUERY);
                List<QaCitation> vectorHits = chunkRepo.searchByVector(repositoryId, queryVector, 5);
                combinedCitations.addAll(vectorHits);
            } catch (Exception e) {
                log.debug("Vector search error for question: {}", e.getMessage());
            }
        }

        // 2. Full-Text Search fallback/hybrid
        List<String> keywords = Arrays.stream(question.split("\\s+"))
                .map(String::trim)
                .filter(w -> w.length() > 2)
                .toList();

        List<QaCitation> textHits = chunkRepo.searchByText(repositoryId, keywords, 6);
        combinedCitations.addAll(textHits);

        List<QaCitation> citationList = new ArrayList<>(combinedCitations).stream().limit(6).toList();

        if (citationList.isEmpty()) {
            return new QaResponse(
                    question,
                    "No matching code or documentation found in the indexed repository for this query.",
                    List.of(),
                    false
            );
        }

        // 3. AI synthesis if LLM is active
        if (llm.isPresent()) {
            try {
                String systemPrompt = """
                        You are StackTrial AI Architecture & Code Assistant.
                        Answer the user's question about the repository using ONLY the provided code snippets and files.
                        Cite files explicitly in the format `[filename]`.
                        If the snippets do not contain enough information, state what is known and what is missing.
                        """;

                StringBuilder contextBuilder = new StringBuilder("REPOSITORY CONTEXT SNIPPETS:\n\n");
                for (QaCitation c : citationList) {
                    contextBuilder.append("--- File: ").append(c.filePath());
                    if (c.symbolName() != null && !c.symbolName().isBlank()) {
                        contextBuilder.append(" (").append(c.symbolName()).append(")");
                    }
                    contextBuilder.append(" ---\n").append(c.snippet()).append("\n\n");
                }
                contextBuilder.append("USER QUESTION: ").append(question);

                String answer = llm.get().generate(systemPrompt, contextBuilder.toString());
                return new QaResponse(question, answer, citationList, true);
            } catch (Exception e) {
                log.warn("LLM generation failed for repository Q&A: {}", e.getMessage());
            }
        }

        // Grounded deterministic fallback if LLM is offline
        StringBuilder sb = new StringBuilder();
        sb.append("### Grounded Codebase Results\n\n");
        sb.append("Relevant code chunks matching `").append(question).append("` were retrieved from ").append(citationList.size()).append(" source files:\n\n");
        for (QaCitation c : citationList) {
            sb.append("- **`").append(c.filePath()).append("`**");
            if (c.symbolName() != null) {
                sb.append(" (`").append(c.symbolName()).append("`)");
            }
            sb.append("\n```\n").append(c.snippet().strip()).append("\n```\n\n");
        }

        return new QaResponse(question, sb.toString(), citationList, false);
    }

    private void chunkFile(UUID repositoryId, Path repoRoot, Path file, List<RepositoryChunk> chunks) {
        try {
            String content = Files.readString(file);
            if (content.isBlank()) return;

            String relPath = repoRoot.relativize(file).toString().replace('\\', '/');
            String symbol = extractSymbol(content);

            if (content.length() <= MAX_CHUNK_CHARS) {
                chunks.add(new RepositoryChunk(
                        UUID.randomUUID(),
                        repositoryId,
                        relPath,
                        0,
                        symbol,
                        content,
                        Instant.now()
                ));
                return;
            }

            int index = 0;
            int start = 0;
            while (start < content.length()) {
                int end = Math.min(start + MAX_CHUNK_CHARS, content.length());
                String chunkText = content.substring(start, end);
                chunks.add(new RepositoryChunk(
                        UUID.randomUUID(),
                        repositoryId,
                        relPath,
                        index++,
                        symbol,
                        chunkText,
                        Instant.now()
                ));
                start += (MAX_CHUNK_CHARS - CHUNK_OVERLAP);
            }
        } catch (Exception e) {
            log.debug("Skipping chunk for file {}: {}", file, e.getMessage());
        }
    }

    private String extractSymbol(String content) {
        Matcher m = CLASS_OR_METHOD.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
