package com.jcode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Model configuration entry stored in config.json.
 * Type can be "long-context" (coding tasks) or "reasoning" (planning).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelConfig(
        String id,
        String type,
        String provider,
        String providerUrl
) {
    public static final String TYPE_LONG_CONTEXT = "long-context";
    public static final String TYPE_REASONING = "reasoning";
}
