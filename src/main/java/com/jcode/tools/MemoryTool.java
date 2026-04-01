package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.jcode.MemoryManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool for the LLM to save, recall, list, and delete persistent memories.
 * Memories survive across sessions and can be global or project-scoped.
 */
public class MemoryTool implements Tool {

    private final MemoryManager memoryManager;

    public MemoryTool(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public boolean isReadOnly() {
        return false; // can write memory files
    }

    @Override
    public String description() {
        return "Save, recall, list, or delete persistent memories that survive across sessions. "
                + "Use action='save' to store something important (user preferences, project decisions, feedback). "
                + "Use action='recall' with a query to search memories. "
                + "Use action='list' to show all stored memories. "
                + "Use action='delete' with a name to remove a memory. "
                + "Memory types: user (preferences/role), feedback (corrections/confirmations), "
                + "project (decisions/context), reference (external links/resources).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("action"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of(
                "type", "string",
                "description", "Action to perform: save, recall, list, delete",
                "enum", List.of("save", "recall", "list", "delete")));
        props.put("name", Map.of(
                "type", "string",
                "description", "Memory name/identifier (required for save and delete)"));
        props.put("type", Map.of(
                "type", "string",
                "description", "Memory type: user, feedback, project, reference (for save)",
                "enum", List.of("user", "feedback", "project", "reference")));
        props.put("content", Map.of(
                "type", "string",
                "description", "Memory content to save (required for save)"));
        props.put("query", Map.of(
                "type", "string",
                "description", "Search query (for recall)"));
        props.put("project", Map.of(
                "type", "boolean",
                "description", "Save to project-local memory instead of global (default: false)"));

        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String action = args.get("action").asText();

        return switch (action) {
            case "save" -> {
                String name = args.has("name") ? args.get("name").asText() : null;
                String type = args.has("type") ? args.get("type").asText() : "project";
                String content = args.has("content") ? args.get("content").asText() : null;
                boolean project = args.has("project") && args.get("project").asBoolean();

                if (name == null || name.isBlank()) yield "Error: 'name' is required for save";
                if (content == null || content.isBlank()) yield "Error: 'content' is required for save";

                yield memoryManager.saveMemory(name, type, content, project);
            }
            case "recall" -> {
                String query = args.has("query") ? args.get("query").asText() : "";
                if (query.isBlank()) yield "Error: 'query' is required for recall";
                yield memoryManager.recallMemories(query);
            }
            case "list" -> memoryManager.listMemories();
            case "delete" -> {
                String name = args.has("name") ? args.get("name").asText() : null;
                if (name == null || name.isBlank()) yield "Error: 'name' is required for delete";
                yield memoryManager.deleteMemory(name);
            }
            default -> "Error: Unknown action: " + action + ". Use: save, recall, list, delete";
        };
    }
}
