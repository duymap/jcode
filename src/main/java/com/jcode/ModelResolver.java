package com.jcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcode.model.Model;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Resolves LLM models from local providers (LM Studio, Ollama).
 */
public final class ModelResolver {

    public static final Map<String, String> LOCAL_PROVIDERS = Map.of(
            "lm-studio", "http://127.0.0.1:1234/v1",
            "ollama", "http://127.0.0.1:11434/v1"
    );

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelResolver() {}

    /**
     * Discover available models from a local provider endpoint.
     */
    public static List<String> discoverModels(String baseUrl) {
        List<String> models = new ArrayList<>();
        Request request = new Request.Builder()
                .url(baseUrl + "/models")
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return models;
            JsonNode root = MAPPER.readTree(response.body().string());
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode node : data) {
                    JsonNode id = node.get("id");
                    if (id != null) models.add(id.asText());
                }
            }
        } catch (IOException e) {
            // Provider not available
        }
        return models;
    }

    /**
     * Auto-discover the first loaded model from a local provider.
     */
    public static String discoverFirstModel(String baseUrl) {
        List<String> models = discoverModels(baseUrl);
        return models.isEmpty() ? null : models.get(0);
    }

    /**
     * Resolve a model based on provider and optional model ID / URL.
     */
    public static Model resolveModel(String provider, String modelId, String url) {
        String defaultUrl = LOCAL_PROVIDERS.get(provider);
        if (defaultUrl == null) {
            throw new IllegalArgumentException(
                    "Unknown provider \"%s\". Supported: %s".formatted(
                            provider, String.join(", ", LOCAL_PROVIDERS.keySet())));
        }

        String baseUrl = url != null ? url : defaultUrl;
        String id = modelId;

        if (id == null || id.isBlank()) {
            id = discoverFirstModel(baseUrl);
            if (id == null) {
                String displayName = "ollama".equals(provider) ? "Ollama" : "LM Studio";
                throw new RuntimeException(
                        "No models loaded in %s at %s. Make sure %s is running with a model loaded."
                                .formatted(displayName, baseUrl, displayName));
            }
        }

        return new Model(id, baseUrl, provider);
    }
}
