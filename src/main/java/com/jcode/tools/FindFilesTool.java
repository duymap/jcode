package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class FindFilesTool implements Tool {

    private static final int DEFAULT_LIMIT = 1000;

    @Override
    public String name() {
        return "find";
    }

    @Override
    public String description() {
        return "Search for files by glob pattern. Respects .gitignore. "
                + "Returns matching file paths relative to the search directory.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("pattern"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of("type", "string", "description", "Glob pattern (e.g. \"*.ts\", \"**/*.json\")"));
        props.put("path", Map.of("type", "string", "description", "Directory to search (default: cwd)"));
        props.put("limit", Map.of("type", "integer", "description", "Max results (default: 1000)"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String pattern = args.get("pattern").asText();
        String searchPath = args.has("path") ? args.get("path").asText() : ".";
        int limit = args.has("limit") ? args.get("limit").asInt(DEFAULT_LIMIT) : DEFAULT_LIMIT;

        Path resolved = ReadFileTool.resolvePath(searchPath, cwd);

        if (!Files.exists(resolved) || !Files.isDirectory(resolved)) {
            return "Error: Directory not found: " + resolved;
        }

        // Try fd first, fall back to find command, then Java walk
        List<String> results = tryFd(resolved, pattern, limit);
        if (results == null) {
            results = tryFind(resolved, pattern, limit);
        }
        if (results == null) {
            results = javaFind(resolved, pattern, limit);
        }

        if (results.isEmpty()) {
            return "No files found matching pattern: " + pattern;
        }

        StringBuilder sb = new StringBuilder();
        for (String result : results) {
            // Make relative to search dir
            if (result.startsWith(resolved.toString())) {
                result = resolved.relativize(Path.of(result)).toString();
            }
            sb.append(result).append('\n');
        }

        if (results.size() >= limit) {
            sb.append("\n[Limit reached: showing first %d results]".formatted(limit));
        }

        return sb.toString();
    }

    private List<String> tryFd(Path dir, String pattern, int limit) {
        try {
            List<String> cmd = List.of("fd", "--glob", pattern, "--max-results", String.valueOf(limit), dir.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            List<String> results = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && results.size() < limit) {
                    if (!line.isBlank()) results.add(line);
                }
            }
            process.waitFor(30, TimeUnit.SECONDS);
            return process.exitValue() == 0 || !results.isEmpty() ? results : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> tryFind(Path dir, String pattern, int limit) {
        try {
            List<String> cmd = List.of("find", dir.toString(), "-name", pattern, "-maxdepth", "10");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            List<String> results = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && results.size() < limit) {
                    if (!line.isBlank()) results.add(line);
                }
            }
            process.waitFor(30, TimeUnit.SECONDS);
            return results.isEmpty() ? null : results;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> javaFind(Path dir, String pattern, int limit) {
        try {
            // Simple glob matching via Java NIO
            java.nio.file.PathMatcher matcher = dir.getFileSystem()
                    .getPathMatcher("glob:" + pattern);
            List<String> results = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir, 10)) {
                walk.filter(p -> matcher.matches(p.getFileName()))
                        .limit(limit)
                        .forEach(p -> results.add(p.toString()));
            }
            return results;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
