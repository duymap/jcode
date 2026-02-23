package com.jcode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Application configuration stored in ~/.jcode/config.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JCodeConfig {
    private List<ModelConfig> models;

    public JCodeConfig() {
        this.models = new ArrayList<>();
    }

    public JCodeConfig(List<ModelConfig> models) {
        this.models = models != null ? models : new ArrayList<>();
    }

    public List<ModelConfig> getModels() {
        return models;
    }

    public void setModels(List<ModelConfig> models) {
        this.models = models != null ? models : new ArrayList<>();
    }

    /**
     * Get a model by type from the config.
     */
    public ModelConfig getModelByType(String type) {
        return models.stream()
                .filter(m -> type.equals(m.type()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the long-context model. Falls back to first model.
     */
    public ModelConfig getDefaultModel() {
        ModelConfig m = getModelByType(ModelConfig.TYPE_LONG_CONTEXT);
        return m != null ? m : (models.isEmpty() ? null : models.get(0));
    }

    /**
     * Get the reasoning model. Falls back to default model.
     */
    public ModelConfig getReasoningModel() {
        ModelConfig m = getModelByType(ModelConfig.TYPE_REASONING);
        return m != null ? m : getDefaultModel();
    }
}
