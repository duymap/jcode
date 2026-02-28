package com.jcode;

import com.jcode.model.JCodeConfig;
import com.jcode.model.ModelConfig;
import com.jcode.model.Model;
import com.jcode.model.ReasoningModelConfig;
import com.jcode.tui.AppRunner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "jcode",
        description = "A lightweight, terminal-based AI coding agent powered by LLMs",
        version = "0.2.0",
        mixinStandardHelpOptions = true,
        subcommands = {JCodeCli.SetupCommand.class}
)
public class JCodeCli implements Callable<Integer> {

    private static final Map<String, String> DEFAULT_PROVIDER_URLS = Map.of(
            "lm-studio", "http://127.0.0.1:1234/v1",
            "ollama", "http://127.0.0.1:11434/v1"
    );

    @Option(names = {"-m", "--model"}, description = "Model name/ID (overrides config)")
    private String modelOpt;

    @Option(names = {"-P", "--provider"}, description = "LLM provider (lm-studio, ollama)")
    private String providerOpt;

    @Option(names = {"-u", "--url"}, description = "API endpoint URL")
    private String urlOpt;

    @Option(names = {"-r", "--readonly"}, description = "Read-only mode (no write/bash tools)")
    private boolean readonly;

    @Option(names = {"-p", "--print"}, description = "One-shot mode: print response and exit")
    private String printPrompt;

    @Option(names = {"--no-planning"}, description = "Disable automatic planning step")
    private boolean noPlanning;

    @Override
    public Integer call() {
        try {
            JCodeConfig config = Config.loadConfig();
            ModelConfig defaultModelConfig = config.getDefaultModel();
            ModelConfig reasoningModelConfig = config.getReasoningModel();

            // First-run check
            boolean hasConfiguredModels = !config.getModels().isEmpty();
            if (!Config.modelsJsonExists() && !hasConfiguredModels
                    && providerOpt == null && modelOpt == null) {
                System.err.println(
                        "\n  \u001b[33mNo models configured. Run `jcode setup` to get started.\u001b[0m\n");
                return 1;
            }

            // Resolve provider/model/url from CLI options or config
            String provider = providerOpt != null ? providerOpt
                    : (defaultModelConfig != null ? defaultModelConfig.provider() : "lm-studio");
            String modelId = modelOpt != null ? modelOpt
                    : (defaultModelConfig != null ? defaultModelConfig.id() : null);
            String url = urlOpt != null ? urlOpt
                    : (defaultModelConfig != null ? defaultModelConfig.providerUrl() : null);

            Model model = ModelResolver.resolveModel(provider, modelId, url);

            // Resolve reasoning model
            ReasoningModelConfig reasoningModel = null;
            if (reasoningModelConfig != null) {
                String reasoningUrl = reasoningModelConfig.providerUrl();
                if (reasoningUrl == null) {
                    reasoningUrl = DEFAULT_PROVIDER_URLS.getOrDefault(
                            reasoningModelConfig.provider(),
                            DEFAULT_PROVIDER_URLS.get("lm-studio"));
                }
                reasoningModel = new ReasoningModelConfig(reasoningUrl, reasoningModelConfig.id());
            }

            boolean disablePlanning = noPlanning || printPrompt != null;

            AgentSession session = new AgentSession(
                    model,
                    System.getProperty("user.dir"),
                    readonly,
                    disablePlanning,
                    reasoningModel
            );

            String modelFallbackMessage = null;
            if (modelId != null && modelOpt == null && !modelId.equals(model.id())) {
                modelFallbackMessage = "Configured model not found, using: " + model.id();
            }

            AppRunner.run(session, printPrompt, modelFallbackMessage);
            return 0;

        } catch (Exception e) {
            System.err.println("\n  \u001b[31mError: " + e.getMessage() + "\u001b[0m\n");
            return 1;
        }
    }

    @Command(name = "setup", description = "Interactive setup wizard to configure your LLM provider")
    static class SetupCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            try {
                SetupWizard.runSetup();
                return 0;
            } catch (Exception e) {
                System.err.println("\n  \u001b[31mError: " + e.getMessage() + "\u001b[0m\n");
                return 1;
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JCodeCli()).execute(args);
        System.exit(exitCode);
    }
}
