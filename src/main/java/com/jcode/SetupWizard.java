package com.jcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcode.model.JCodeConfig;
import com.jcode.model.ModelConfig;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Interactive setup wizard for configuring LLM providers and models.
 */
public class SetupWizard {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void runSetup() throws Exception {
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            PrintWriter out = terminal.writer();
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();

            out.println("\n  \u001b[1mjcode setup\u001b[0m\n");
            out.flush();

            // Step 1: Select provider
            List<String> providers = List.of("lm-studio", "ollama");
            String provider = selectWithArrows(terminal, providers, "  Select your local LLM provider:\n");

            out.println("\n  \u001b[2mUsing provider: " + provider + "\u001b[0m\n");
            out.flush();

            String defaultUrl = ModelResolver.LOCAL_PROVIDERS.get(provider);
            String displayName = "ollama".equals(provider) ? "Ollama" : "LM Studio";

            // Step 2: Get URL
            String urlAnswer = lineReader.readLine(
                    "\u001b[36m  " + displayName + " URL [" + defaultUrl + "]: \u001b[0m");
            String baseUrl = urlAnswer.isBlank() ? defaultUrl : urlAnswer.trim();

            // Step 3: Discover models
            out.println("\n  \u001b[2mDiscovering models from " + displayName + "...\u001b[0m");
            out.flush();
            List<String> models = ModelResolver.discoverModels(baseUrl);

            if (models.isEmpty()) {
                out.println("\n  \u001b[33mNo models found. Make sure " + displayName
                        + " is running with a model loaded.\u001b[0m");
                out.println("  \u001b[33mAborting setup.\u001b[0m\n");
                out.flush();
                return;
            }

            List<ModelConfig> configuredModels = new ArrayList<>();

            // Step 4: Select long-context model
            out.println("\n  \u001b[1mStep 1: Configure long-context model\u001b[0m\n");
            out.println("  \u001b[2mThis model handles coding tasks, file operations, and tool usage.\u001b[0m");
            out.println("  \u001b[2mRecommended: Models with large context windows (32K+ tokens)\u001b[0m\n");
            out.flush();

            String defaultModel = selectWithArrows(terminal, models,
                    "\u001b[32m  Select model for long-context (coding):\u001b[0m\n");

            configuredModels.add(new ModelConfig(
                    defaultModel, ModelConfig.TYPE_LONG_CONTEXT, provider, baseUrl));

            // Step 5: Optional reasoning model
            out.println("\n  \u001b[1mStep 2: Configure reasoning model (optional)\u001b[0m\n");
            out.println("  \u001b[2mThis model generates execution plans before coding tasks.\u001b[0m");
            out.println("  \u001b[2mRecommended: Fast reasoning models (DeepSeek-R1, o1-mini, etc.)\u001b[0m\n");
            out.flush();

            String setupReasoning = lineReader.readLine(
                    "\u001b[36m  Configure a separate reasoning model? [y/N]: \u001b[0m");

            if ("y".equalsIgnoreCase(setupReasoning.trim()) || "yes".equalsIgnoreCase(setupReasoning.trim())) {
                String sameUrl = lineReader.readLine(
                        "\u001b[36m  Use same URL for reasoning model? [Y/n]: \u001b[0m");

                String reasoningUrl = baseUrl;
                List<String> reasoningModels = models;

                if ("n".equalsIgnoreCase(sameUrl.trim()) || "no".equalsIgnoreCase(sameUrl.trim())) {
                    String newUrl = lineReader.readLine(
                            "\u001b[36m  Reasoning model URL [" + defaultUrl + "]: \u001b[0m");
                    reasoningUrl = newUrl.isBlank() ? defaultUrl : newUrl.trim();

                    if (!reasoningUrl.equals(baseUrl)) {
                        out.println("\n  \u001b[2mDiscovering models from reasoning endpoint...\u001b[0m");
                        out.flush();
                        reasoningModels = ModelResolver.discoverModels(reasoningUrl);
                        if (reasoningModels.isEmpty()) {
                            out.println("  \u001b[33mNo models found at reasoning endpoint. Skipping.\u001b[0m");
                            out.flush();
                            reasoningModels = List.of();
                        }
                    }
                }

                if (!reasoningModels.isEmpty()) {
                    // Show other models first, then the default
                    List<String> reasoningOptions = new ArrayList<>(reasoningModels.stream()
                            .filter(m -> !m.equals(defaultModel))
                            .toList());
                    reasoningOptions.add(defaultModel);

                    String reasoningModelId = selectWithArrows(terminal, reasoningOptions,
                            "\u001b[32m  Select model for reasoning/planning:\u001b[0m\n");

                    if (!reasoningModelId.equals(defaultModel)) {
                        configuredModels.add(new ModelConfig(
                                reasoningModelId, ModelConfig.TYPE_REASONING, provider, reasoningUrl));
                        out.println("\n  \u001b[32mReasoning model configured: " + reasoningModelId + "\u001b[0m");
                    } else {
                        out.println("\n  \u001b[2mUsing same model for reasoning (no separate config needed).\u001b[0m");
                    }
                }
            } else {
                out.println("  \u001b[2mSkipping reasoning model setup (will use long-context model for planning).\u001b[0m");
            }
            out.flush();

            // Save config
            writeModelsJson(provider, baseUrl, configuredModels);
            saveSetupConfig(configuredModels);

            // Summary
            out.println("\n  \u001b[1mConfiguration summary:\u001b[0m\n");
            for (ModelConfig m : configuredModels) {
                String typeLabel = ModelConfig.TYPE_REASONING.equals(m.type())
                        ? "reasoning (planning)"
                        : "long-context (coding)";
                out.println("    - " + m.id() + " [" + typeLabel + "]");
            }
            out.println("\n  \u001b[32mSetup complete! Run `jcode` to start.\u001b[0m\n");
            out.flush();
        }
    }

    /**
     * Arrow-key selection menu using JLine terminal raw mode.
     */
    static String selectWithArrows(Terminal terminal, List<String> items, String label) throws IOException {
        PrintWriter out = terminal.writer();
        int selected = 0;
        boolean rendered = false;

        out.print(label);
        out.flush();

        // Render function
        Runnable render = () -> {};

        // Initial render
        for (int i = 0; i < items.size(); i++) {
            String cursor = i == selected ? "\u001b[36m>\u001b[0m" : " ";
            String text = i == selected
                    ? "\u001b[36m" + items.get(i) + "\u001b[0m"
                    : "\u001b[2m" + items.get(i) + "\u001b[0m";
            out.println("    " + cursor + " " + text);
        }
        out.flush();

        Terminal.SignalHandler prevHandler = terminal.handle(Terminal.Signal.INT, s -> {
            System.exit(0);
        });

        try {
            terminal.enterRawMode();
            NonBlockingReader reader = terminal.reader();

            while (true) {
                int c = reader.read();

                if (c == 27) { // ESC sequence
                    int c2 = reader.read(50);
                    if (c2 == '[') {
                        int c3 = reader.read(50);
                        if (c3 == 'A') { // Up
                            selected = (selected - 1 + items.size()) % items.size();
                        } else if (c3 == 'B') { // Down
                            selected = (selected + 1) % items.size();
                        }
                    }
                } else if (c == 'k') {
                    selected = (selected - 1 + items.size()) % items.size();
                } else if (c == 'j') {
                    selected = (selected + 1) % items.size();
                } else if (c == '\r' || c == '\n') {
                    out.println();
                    out.flush();
                    return items.get(selected);
                } else if (c == 3) { // Ctrl+C
                    System.exit(0);
                }

                // Re-render: move cursor up and redraw
                out.print("\u001b[" + items.size() + "A");
                for (int i = 0; i < items.size(); i++) {
                    String cursor = i == selected ? "\u001b[36m>\u001b[0m" : " ";
                    String text = i == selected
                            ? "\u001b[36m" + items.get(i) + "\u001b[0m"
                            : "\u001b[2m" + items.get(i) + "\u001b[0m";
                    out.println("\u001b[2K    " + cursor + " " + text);
                }
                out.flush();
            }
        } finally {
            terminal.handle(Terminal.Signal.INT, prevHandler);
        }
    }

    private static void writeModelsJson(String provider, String baseUrl,
                                         List<ModelConfig> configuredModels) throws IOException {
        List<String> modelIds = configuredModels.stream().map(ModelConfig::id).toList();
        List<Map<String, String>> modelsArray = modelIds.stream()
                .map(id -> Map.of("id", id, "name", id))
                .toList();

        Map<String, Object> data = Map.of(
                "providers", Map.of(
                        provider, Map.of(
                                "apiKey", provider,
                                "baseUrl", baseUrl,
                                "api", "openai-completions",
                                "models", modelsArray
                        )
                )
        );

        Files.createDirectories(Config.getConfigDir());
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(Config.getModelsJsonPath().toFile(), data);
        System.out.println("  \u001b[2mWrote " + Config.getModelsJsonPath() + "\u001b[0m");
    }

    private static void saveSetupConfig(List<ModelConfig> models) throws IOException {
        JCodeConfig config = Config.loadConfig();
        config.setModels(models);
        Config.saveConfig(config);
        System.out.println("  \u001b[2mWrote " + Config.getConfigPath() + "\u001b[0m");
    }
}
