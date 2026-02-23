package com.jcode.model;

/**
 * Represents an LLM model with its provider and connection details.
 */
public record Model(
        String id,
        String name,
        String provider,
        String baseUrl,
        int contextWindow,
        int maxTokens
) {
    public Model(String id, String baseUrl, String provider) {
        this(id, id, provider, baseUrl, 128_000, 16_384);
    }
}
