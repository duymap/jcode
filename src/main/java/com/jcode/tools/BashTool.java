package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_LINES = 2000;

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Execute a bash command in the working directory. "
                + "Returns stdout and stderr. Use for running scripts, git, builds, etc.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("command"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", Map.of("type", "string", "description", "Bash command to execute"));
        props.put("timeout", Map.of("type", "integer", "description", "Timeout in seconds (default: 120)"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String command = args.get("command").asText();
        int timeout = args.has("timeout") ? args.get("timeout").asInt(DEFAULT_TIMEOUT_SECONDS) : DEFAULT_TIMEOUT_SECONDS;

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(Path.of(cwd).toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        int lineCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lineCount < MAX_OUTPUT_LINES) {
                    output.append(line).append('\n');
                }
                lineCount++;
            }
        }

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return output + "\n[Timed out after %d seconds]".formatted(timeout);
        }

        int exitCode = process.exitValue();
        if (lineCount > MAX_OUTPUT_LINES) {
            output.append("\n[Truncated: showed first %d of %d lines]".formatted(MAX_OUTPUT_LINES, lineCount));
        }
        if (exitCode != 0) {
            output.append("\n[Exit code: %d]".formatted(exitCode));
        }

        return output.toString();
    }
}
