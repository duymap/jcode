package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.jcode.tui.DiffRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EditFileTool implements Tool {

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Edit a file by replacing an exact text match with new text. "
                + "The oldText must match exactly (including whitespace and indentation).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("path", "oldText", "newText"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of("type", "string", "description", "File path (relative or absolute)"));
        props.put("oldText", Map.of("type", "string", "description", "Exact text to find and replace"));
        props.put("newText", Map.of("type", "string", "description", "Replacement text"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String filePath = args.get("path").asText();
        String oldText = args.get("oldText").asText();
        String newText = args.get("newText").asText();
        Path resolved = ReadFileTool.resolvePath(filePath, cwd);

        if (!Files.exists(resolved)) {
            return "Error: File not found: " + resolved;
        }

        String content = Files.readString(resolved);
        String originalContent = content;

        // Try exact match first
        int idx = content.indexOf(oldText);
        String actualOldText = oldText;
        if (idx == -1) {
            // Try fuzzy match: normalize whitespace at end of lines
            String normalizedContent = normalizeTrailingWhitespace(content);
            String normalizedOld = normalizeTrailingWhitespace(oldText);
            idx = normalizedContent.indexOf(normalizedOld);

            if (idx == -1) {
                return "Error: Could not find the specified text in " + resolved
                        + ". Make sure oldText matches exactly.";
            }

            // Find the corresponding section in original content
            String before = normalizedContent.substring(0, idx);
            int originalStart = mapNormalizedIndex(content, before);
            String after = normalizedContent.substring(idx + normalizedOld.length());
            int originalEnd = content.length() - mapNormalizedSuffix(content, after);

            actualOldText = content.substring(originalStart, originalEnd);
            idx = originalStart;
            content = content.substring(0, originalStart) + newText + content.substring(originalEnd);
        } else {
            // Check for duplicate matches
            int secondIdx = content.indexOf(oldText, idx + 1);
            if (secondIdx != -1) {
                return "Error: Multiple matches found for oldText in " + resolved
                        + ". Provide more context to make the match unique.";
            }
            content = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
        }

        Files.writeString(resolved, content);

        int lineNum = originalContent.substring(0, Math.min(idx, originalContent.length()))
                .split("\n", -1).length;

        String displayPath = filePath;
        String diff = DiffRenderer.render(displayPath, actualOldText, newText, lineNum);
        String llmResult = "Edited %s (change at line %d)".formatted(resolved, lineNum);
        return llmResult + "@@DIFF@@" + diff;
    }

    private static String normalizeTrailingWhitespace(String text) {
        return text.lines()
                .map(String::stripTrailing)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static int mapNormalizedIndex(String original, String normalizedPrefix) {
        // Approximate: count lines in normalized prefix, find same position in original
        int lines = normalizedPrefix.split("\n", -1).length - 1;
        int idx = 0;
        for (int i = 0; i < lines && idx < original.length(); i++) {
            idx = original.indexOf('\n', idx) + 1;
            if (idx == 0) break;
        }
        // Add remaining chars on the last line
        int lastNewline = normalizedPrefix.lastIndexOf('\n');
        int remaining = lastNewline >= 0 ? normalizedPrefix.length() - lastNewline - 1 : normalizedPrefix.length();
        return idx + remaining;
    }

    private static int mapNormalizedSuffix(String original, String normalizedSuffix) {
        // Map from the end
        return mapNormalizedIndex(
                new StringBuilder(original).reverse().toString(),
                new StringBuilder(normalizedSuffix).reverse().toString()
        );
    }
}
