package com.stacktrial.explanation;

/** One section of a curated knowledge document, identified by a stable id such as {@code KB-SB30-04}. */
public record KnowledgeChunk(String id, String sourceTitle, String sourceUrl, String title, String content) {
}
