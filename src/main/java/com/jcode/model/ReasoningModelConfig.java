package com.jcode.model;

/**
 * Configuration for a separate reasoning model used in planning.
 */
public record ReasoningModelConfig(
        String baseUrl,
        String modelId
) {
}
