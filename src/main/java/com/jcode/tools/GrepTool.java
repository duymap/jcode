package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GrepTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LINE_LENGTH = 500;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Search file contents using a regex pattern. "
                + "Respects .gitignore. Returns matching lines with file paths and line numbers.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("pattern"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of("type", "string", "description", "Regex or literal search pattern"));
        props.put("path", Map.of("type", "string", "description", "Directory or file to search (default: cwd)"));
        props.put("glob", Map.of("type", "string", "description", "Filter by glob pattern (e.g. \"*.ts\")"));
        props.put("ignoreCase", Map.of("type", "boolean", "description", "Case-insensitive search"));
        props.put("context", Map.of("type", "integer", "description", "Lines of context around matches"));
        props.put("limit", Map.of("type", "integer", "description", "Max matches to return (default: 100)"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String pattern = args.get("pattern").asText();
        String searchPath = args.has("path") ? args.get("path").asText() : ".";
        boolean ignoreCase = args.has("ignoreCase") && args.get("ignoreCase").asBoolean();
        int context = args.has("context") ? args.get("context").asInt(0) : 0;
        int limit = args.has("limit") ? args.get("limit").asInt(DEFAULT_LIMIT) : DEFAULT_LIMIT;
        String glob = args.has("glob") ? args.get("glob").asText() : null;

        Path resolved = ReadFileTool.resolvePath(searchPath, cwd);

        // Build grep command - try ripgrep first, fall back to grep
        List<String> cmd = new ArrayList<>();
        cmd.add("grep");
        cmd.add("-rn");
        if (ignoreCase) cmd.add("-i");
        if (context > 0) {
            cmd.add("-C");
            cmd.add(String.valueOf(context));
        }
        if (glob != null) {
            cmd.add("--include=" + glob);
        }
        cmd.add("-m");
        cmd.add(String.valueOf(limit));
        cmd.add(pattern);
        cmd.add(resolved.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(Path.of(cwd).toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        int matchCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + " [truncated]";
                }
                // Make paths relative to cwd
                String cwdPrefix = cwd.endsWith("/") ? cwd : cwd + "/";
                if (line.startsWith(cwdPrefix)) {
                    line = line.substring(cwdPrefix.length());
                }
                output.append(line).append('\n');
                matchCount++;
            }
        }

        process.waitFor(30, TimeUnit.SECONDS);

        if (matchCount == 0) {
            return "No matches found for pattern: " + pattern;
        }

        if (matchCount >= limit) {
            output.append("\n[Limit reached: showing first %d matches]".formatted(limit));
        }

        return output.toString();
    }
}
