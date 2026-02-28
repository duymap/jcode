package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.jcode.tui.DiffRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "Write content to a file. Creates the file if it doesn't exist, "
                + "overwrites if it does. Auto-creates parent directories.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("path", "content"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", "File path (relative or absolute)"));
        props.put("content", Map.of("type", "string", "description", "Content to write"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String filePath = args.get("path").asText();
        String content = args.get("content").asText();
        Path resolved = ReadFileTool.resolvePath(filePath, cwd);

        boolean existed = Files.exists(resolved);
        String oldContent = existed ? Files.readString(resolved) : null;

        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, content);

        long bytes = content.getBytes().length;
        String llmResult = "Wrote %d bytes to %s".formatted(bytes, resolved);

        String diff;
        if (existed && oldContent != null) {
            diff = DiffRenderer.render(filePath, oldContent, content, 1);
        } else {
            diff = DiffRenderer.renderNewFile(filePath, content);
        }
        return llmResult + "@@DIFF@@" + diff;
    }
}
