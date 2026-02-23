package com.jcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jcode.model.JCodeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration management - loads/saves from ~/.jcode/config.json.
 */
public final class Config {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Config() {}

    public static Path getConfigDir() {
        return Path.of(System.getProperty("user.home"), ".jcode");
    }

    public static Path getConfigPath() {
        return getConfigDir().resolve("config.json");
    }

    public static Path getModelsJsonPath() {
        return getConfigDir().resolve("models.json");
    }

    public static boolean modelsJsonExists() {
        return Files.exists(getModelsJsonPath());
    }

    public static JCodeConfig loadConfig() {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                return MAPPER.readValue(configPath.toFile(), JCodeConfig.class);
            } catch (IOException e) {
                return new JCodeConfig();
            }
        }
        return new JCodeConfig();
    }

    public static void saveConfig(JCodeConfig config) throws IOException {
        Path configDir = getConfigDir();
        Files.createDirectories(configDir);
        MAPPER.writeValue(getConfigPath().toFile(), config);
    }
}
