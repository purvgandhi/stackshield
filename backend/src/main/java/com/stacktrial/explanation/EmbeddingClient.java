package com.stacktrial.explanation;

public interface EmbeddingClient {

    enum Purpose { DOCUMENT, QUERY }

    float[] embed(String text, Purpose purpose);
}
