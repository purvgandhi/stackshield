package com.stacktrial.explanation;

/** @param score cosine similarity (vector) or ts_rank (full text); higher is better */
public record RetrievedChunk(KnowledgeChunk chunk, double score) {
}
