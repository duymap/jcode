package com.jcode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Persistent memory system — stores durable context across sessions.
 * Memories are stored as markdown files in ~/.jcode/memory/ (global)
 * and .jcode/memory/ (project-local).
 *
 * Memory types: user, feedback, project, reference
 */
public class MemoryManager {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path globalMemoryDir;
    private final Path projectMemoryDir;

    public MemoryManager(String cwd) {
        this.globalMemoryDir = Config.getConfigDir().resolve("memory");
        this.projectMemoryDir = Path.of(cwd, ".jcode", "memory");
    }

    /**
     * Save a memory to disk.
     *
     * @param name    short identifier (used for filename)
     * @param type    memory type: user, feedback, project, reference
     * @param content the memory content
     * @param project if true, save to project-local memory; else global
     * @return confirmation message
     */
    public String saveMemory(String name, String type, String content, boolean project) throws IOException {
        Path dir = project ? projectMemoryDir : globalMemoryDir;
        Files.createDirectories(dir);

        // Sanitize filename
        String filename = name.toLowerCase()
                .replaceAll("[^a-z0-9_-]", "_")
                .replaceAll("_+", "_") + ".md";

        Path memoryFile = dir.resolve(filename);

        String description = content.length() > 80 ? content.substring(0, 80) + "..." : content;

        String fileContent = "---\nname: %s\ntype: %s\ndescription: %s\ndate: %s\n---\n\n%s\n"
                .formatted(name, type, description, LocalDateTime.now().format(DATE_FMT), content);

        Files.writeString(memoryFile, fileContent);

        // Update MEMORY.md index
        updateIndex(dir, name, filename, description);

        String scope = project ? "project" : "global";
        return "Saved %s memory '%s' (%s)".formatted(scope, name, filename);
    }

    /**
     * Recall memories by searching content and names.
     *
     * @param query search term (matched against name, type, and content)
     * @return matching memories formatted as text
     */
    public String recallMemories(String query) {
        List<MemoryEntry> results = new ArrayList<>();
        String q = query.toLowerCase();

        // Search both global and project memories
        searchDir(globalMemoryDir, q, "global", results);
        searchDir(projectMemoryDir, q, "project", results);

        if (results.isEmpty()) {
            return "No memories found matching: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found %d memor%s:\n\n".formatted(results.size(), results.size() == 1 ? "y" : "ies"));
        for (MemoryEntry entry : results) {
            sb.append("### [%s] %s (%s)\n".formatted(entry.type, entry.name, entry.scope));
            sb.append(entry.content).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * List all memories (brief index).
     */
    public String listMemories() {
        List<MemoryEntry> all = new ArrayList<>();
        searchDir(globalMemoryDir, null, "global", all);
        searchDir(projectMemoryDir, null, "project", all);

        if (all.isEmpty()) {
            return "No memories stored yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Memories (%d total):\n\n".formatted(all.size()));

        // Group by type
        Map<String, List<MemoryEntry>> byType = new LinkedHashMap<>();
        for (MemoryEntry e : all) {
            byType.computeIfAbsent(e.type, k -> new ArrayList<>()).add(e);
        }

        for (var entry : byType.entrySet()) {
            sb.append("**%s:**\n".formatted(entry.getKey()));
            for (MemoryEntry e : entry.getValue()) {
                sb.append("  - %s (%s) — %s\n".formatted(e.name, e.scope, e.description));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Delete a memory by name.
     */
    public String deleteMemory(String name) {
        String filename = name.toLowerCase()
                .replaceAll("[^a-z0-9_-]", "_")
                .replaceAll("_+", "_") + ".md";

        boolean deleted = false;

        // Try both locations
        for (Path dir : new Path[]{projectMemoryDir, globalMemoryDir}) {
            Path file = dir.resolve(filename);
            if (Files.exists(file)) {
                try {
                    Files.delete(file);
                    rebuildIndex(dir);
                    deleted = true;
                } catch (IOException e) {
                    // continue
                }
            }
        }

        return deleted ? "Deleted memory: " + name : "Memory not found: " + name;
    }

    /**
     * Load all memories as context for the system prompt.
     * Returns null if no memories exist.
     */
    public String loadMemoriesForPrompt() {
        List<MemoryEntry> all = new ArrayList<>();
        searchDir(globalMemoryDir, null, "global", all);
        searchDir(projectMemoryDir, null, "project", all);

        if (all.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : all) {
            sb.append("- [%s/%s] %s: %s\n".formatted(e.scope, e.type, e.name, e.content.strip()));
        }
        return sb.toString().trim();
    }

    // --- Internal ---

    private void searchDir(Path dir, String query, String scope, List<MemoryEntry> results) {
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equals("MEMORY.md"))
                    .forEach(p -> {
                        try {
                            String raw = Files.readString(p);
                            MemoryEntry entry = parseMemoryFile(raw, scope);
                            if (entry != null) {
                                if (query == null || matches(entry, query)) {
                                    results.add(entry);
                                }
                            }
                        } catch (IOException e) {
                            // skip unreadable
                        }
                    });
        } catch (IOException e) {
            // dir not accessible
        }
    }

    private boolean matches(MemoryEntry entry, String query) {
        return entry.name.toLowerCase().contains(query)
                || entry.type.toLowerCase().contains(query)
                || entry.content.toLowerCase().contains(query)
                || entry.description.toLowerCase().contains(query);
    }

    private MemoryEntry parseMemoryFile(String raw, String scope) {
        // Parse frontmatter
        if (!raw.startsWith("---")) return null;
        int endFm = raw.indexOf("---", 3);
        if (endFm < 0) return null;

        String frontmatter = raw.substring(3, endFm).trim();
        String content = raw.substring(endFm + 3).trim();

        String name = extractField(frontmatter, "name");
        String type = extractField(frontmatter, "type");
        String description = extractField(frontmatter, "description");

        if (name == null) name = "unnamed";
        if (type == null) type = "general";
        if (description == null) description = content.length() > 80 ? content.substring(0, 80) : content;

        return new MemoryEntry(name, type, description, content, scope);
    }

    private String extractField(String frontmatter, String field) {
        for (String line : frontmatter.split("\n")) {
            if (line.startsWith(field + ":")) {
                return line.substring(field.length() + 1).trim();
            }
        }
        return null;
    }

    private void updateIndex(Path dir, String name, String filename, String description) throws IOException {
        Path indexPath = dir.resolve("MEMORY.md");
        String entry = "- [%s](%s) — %s".formatted(name, filename, description);

        List<String> lines;
        if (Files.exists(indexPath)) {
            lines = new ArrayList<>(Files.readAllLines(indexPath));
            // Remove existing entry with same filename
            lines.removeIf(l -> l.contains("(" + filename + ")"));
        } else {
            lines = new ArrayList<>();
            lines.add("# Memory Index");
            lines.add("");
        }
        lines.add(entry);

        Files.writeString(indexPath, String.join("\n", lines) + "\n");
    }

    private void rebuildIndex(Path dir) throws IOException {
        Path indexPath = dir.resolve("MEMORY.md");
        List<String> lines = new ArrayList<>();
        lines.add("# Memory Index");
        lines.add("");

        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equals("MEMORY.md"))
                        .forEach(p -> {
                            try {
                                String raw = Files.readString(p);
                                MemoryEntry entry = parseMemoryFile(raw, "");
                                if (entry != null) {
                                    lines.add("- [%s](%s) — %s".formatted(
                                            entry.name, p.getFileName(), entry.description));
                                }
                            } catch (IOException e) {
                                // skip
                            }
                        });
            }
        }

        Files.writeString(indexPath, String.join("\n", lines) + "\n");
    }

    private record MemoryEntry(String name, String type, String description, String content, String scope) {}
}
