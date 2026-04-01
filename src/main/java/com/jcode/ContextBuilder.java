package com.jcode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Builds dynamic context to inject into the system prompt:
 * - JCODE.md project-level instructions
 * - Git status, branch, and recent commits
 */
public class ContextBuilder {

    private static final int GIT_TIMEOUT_SECONDS = 5;

    private final String cwd;

    public ContextBuilder(String cwd) {
        this.cwd = cwd;
    }

    /**
     * Build the full dynamic context string to append to the system prompt.
     */
    public String build() {
        StringBuilder ctx = new StringBuilder();

        String jcodeMd = loadJcodeMd();
        if (jcodeMd != null) {
            ctx.append("\n\n## Project Instructions (JCODE.md)\n\n");
            ctx.append(jcodeMd);
        }

        String gitContext = buildGitContext();
        if (gitContext != null) {
            ctx.append("\n\n## Current Git Context\n\n");
            ctx.append(gitContext);
        }

        return ctx.isEmpty() ? null : ctx.toString();
    }

    /**
     * Load JCODE.md from the project root (cwd), if it exists.
     * Also checks for .jcode.md (hidden variant).
     */
    private String loadJcodeMd() {
        for (String name : new String[]{"JCODE.md", ".jcode.md", "jcode.md"}) {
            Path path = Path.of(cwd, name);
            if (Files.isRegularFile(path)) {
                try {
                    String content = Files.readString(path).trim();
                    if (!content.isEmpty()) {
                        return content;
                    }
                } catch (Exception e) {
                    // Silently skip unreadable files
                }
            }
        }
        return null;
    }

    /**
     * Build git context: branch name, status summary, and recent commits.
     */
    private String buildGitContext() {
        // Check if we're in a git repo
        String branch = runGitCommand("git", "rev-parse", "--abbrev-ref", "HEAD");
        if (branch == null) return null;

        StringBuilder git = new StringBuilder();
        git.append("Branch: ").append(branch).append('\n');

        // Git status (short form)
        String status = runGitCommand("git", "status", "--short");
        if (status != null && !status.isEmpty()) {
            // Limit to 20 lines to avoid bloating the prompt
            String[] lines = status.split("\n");
            int show = Math.min(lines.length, 20);
            git.append("\nModified files:\n");
            for (int i = 0; i < show; i++) {
                git.append("  ").append(lines[i]).append('\n');
            }
            if (lines.length > 20) {
                git.append("  ... and ").append(lines.length - 20).append(" more files\n");
            }
        } else {
            git.append("Working tree: clean\n");
        }

        // Recent commits (last 5)
        String log = runGitCommand("git", "log", "--oneline", "-5", "--no-decorate");
        if (log != null && !log.isEmpty()) {
            git.append("\nRecent commits:\n");
            for (String line : log.split("\n")) {
                git.append("  ").append(line).append('\n');
            }
        }

        return git.toString().trim();
    }

    /**
     * Run a git command and return its stdout, or null on failure.
     */
    private String runGitCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(Path.of(cwd).toFile());
            pb.redirectErrorStream(false);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            }

            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            return process.exitValue() == 0 ? output.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
