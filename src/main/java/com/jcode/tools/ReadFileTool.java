package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReadFileTool implements Tool {

    private static final int DEFAULT_MAX_LINES = 2000;

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "Read the contents of a file. Supports text files. "
                + "Use offset and limit for large files.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("path"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", "File path (relative or absolute)"));
        props.put("offset", Map.of("type", "integer", "description", "Line number to start from (1-indexed)"));
        props.put("limit", Map.of("type", "integer", "description", "Maximum number of lines to read"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String filePath = args.get("path").asText();
        Path resolved = resolvePath(filePath, cwd);

        if (!Files.exists(resolved)) {
            return "Error: File not found: " + resolved;
        }
        if (Files.isDirectory(resolved)) {
            return "Error: Path is a directory, not a file: " + resolved;
        }

        int offset = args.has("offset") ? args.get("offset").asInt(1) : 1;
        int limit = args.has("limit") ? args.get("limit").asInt(DEFAULT_MAX_LINES) : DEFAULT_MAX_LINES;

        return readFileContent(resolved, offset, limit);
    }

    private String readFileContent(Path path, int offset, int limit) throws IOException {
        List<String> allLines = Files.readAllLines(path);
        int startIdx = Math.max(0, offset - 1);
        int endIdx = Math.min(allLines.size(), startIdx + limit);

        if (startIdx >= allLines.size()) {
            return "Error: Offset %d exceeds file length (%d lines)".formatted(offset, allLines.size());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < endIdx; i++) {
            sb.append("%d\t%s\n".formatted(i + 1, allLines.get(i)));
        }

        boolean truncated = endIdx < allLines.size();
        if (truncated) {
            sb.append("\n[Truncated: showing lines %d-%d of %d total]".formatted(
                    startIdx + 1, endIdx, allLines.size()));
        }

        return sb.toString();
    }

    static Path resolvePath(String filePath, String cwd) {
        if (filePath.startsWith("~")) {
            filePath = System.getProperty("user.home") + filePath.substring(1);
        }
        Path p = Path.of(filePath);
        return p.isAbsolute() ? p : Path.of(cwd).resolve(p);
    }
}
