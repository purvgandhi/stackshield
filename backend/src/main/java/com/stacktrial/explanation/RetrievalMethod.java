package com.stacktrial.explanation;

public enum RetrievalMethod {
    /** pgvector cosine similarity over Gemini embeddings. */
    VECTOR,
    /** PostgreSQL full-text search; used when no embeddings are available. */
    FULL_TEXT
}
