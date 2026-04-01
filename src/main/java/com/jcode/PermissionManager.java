package com.jcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.jcode.tools.Tool;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Manages tool execution permissions.
 *
 * Modes:
 * - AUTO: read-only tools auto-approved; write tools ask for confirmation on dangerous commands
 * - DEFAULT: all write tools ask for confirmation
 * - BYPASS: everything auto-approved (like old behavior without --readonly)
 */
public class PermissionManager {

    /** Bash commands/patterns that are considered destructive and always require confirmation. */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("\\brm\\s+(-[a-zA-Z]*f|-[a-zA-Z]*r|--force|--recursive)"),
            Pattern.compile("\\brm\\s+-rf\\b"),
            Pattern.compile("\\bgit\\s+(reset\\s+--hard|push\\s+--force|push\\s+-f|clean\\s+-f)"),
            Pattern.compile("\\bgit\\s+branch\\s+-D\\b"),
            Pattern.compile("\\bdrop\\s+(table|database)\\b"),
            Pattern.compile("\\btruncate\\s+table\\b"),
            Pattern.compile("\\bmkfs\\b"),
            Pattern.compile("\\bdd\\s+if="),
            Pattern.compile("\\bchmod\\s+-R\\s+777\\b"),
            Pattern.compile("\\bsudo\\s+rm\\b"),
            Pattern.compile("\\bcurl\\s.*\\|\\s*(ba)?sh\\b")
    );

    /** Tools that always require confirmation in DEFAULT mode. */
    private static final Set<String> WRITE_TOOLS = Set.of("write", "edit", "bash");

    public enum Mode {
        AUTO,       // Smart: auto-approve safe ops, ask for dangerous
        DEFAULT,    // Ask for all write tools
        BYPASS      // Auto-approve everything
    }

    /**
     * Callback for asking the user for confirmation.
     * Implementations should display the prompt and return true if user approves.
     */
    @FunctionalInterface
    public interface ConfirmationHandler {
        boolean askUser(String toolName, String description);
    }

    private final Mode mode;
    private ConfirmationHandler confirmationHandler;

    public PermissionManager(Mode mode) {
        this.mode = mode;
        // Default: auto-deny (safe fallback if no handler is set)
        this.confirmationHandler = (tool, desc) -> false;
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Set the confirmation handler (should be set by AppRunner after terminal is ready).
     */
    public void setConfirmationHandler(ConfirmationHandler handler) {
        this.confirmationHandler = handler;
    }

    /**
     * Check if a tool execution should proceed.
     *
     * @param tool    the tool being called
     * @param args    the arguments
     * @param onText  callback for displaying messages
     * @return true if execution is allowed, false if denied
     */
    public boolean checkPermission(Tool tool, JsonNode args,
                                   java.util.function.Consumer<String> onText) {
        if (mode == Mode.BYPASS) {
            return true;
        }

        // Read-only tools are always allowed
        if (tool.isReadOnly()) {
            return true;
        }

        String toolName = tool.name();

        if (mode == Mode.AUTO) {
            // In AUTO mode, only ask for dangerous bash commands
            if ("bash".equals(toolName)) {
                String command = args.has("command") ? args.get("command").asText() : "";
                if (isDangerousCommand(command)) {
                    return askConfirmation(toolName, describeAction(toolName, args), onText);
                }
            }
            // Non-dangerous write operations auto-approved in AUTO mode
            return true;
        }

        // DEFAULT mode: ask for all write tools
        if (WRITE_TOOLS.contains(toolName)) {
            return askConfirmation(toolName, describeAction(toolName, args), onText);
        }

        return true;
    }

    /**
     * Check if a bash command matches dangerous patterns.
     */
    static boolean isDangerousCommand(String command) {
        if (command == null || command.isEmpty()) return false;
        String lower = command.toLowerCase();
        return DANGEROUS_PATTERNS.stream().anyMatch(p -> p.matcher(lower).find());
    }

    private String describeAction(String toolName, JsonNode args) {
        return switch (toolName) {
            case "bash" -> {
                String cmd = args.has("command") ? args.get("command").asText() : "?";
                if (cmd.length() > 100) cmd = cmd.substring(0, 97) + "...";
                yield "bash: " + cmd;
            }
            case "write" -> "write: " + (args.has("path") ? args.get("path").asText() : "?");
            case "edit" -> "edit: " + (args.has("path") ? args.get("path").asText() : "?");
            default -> toolName;
        };
    }

    private boolean askConfirmation(String toolName, String description,
                                    java.util.function.Consumer<String> onText) {
        onText.accept("\n\u001b[33m  Permission required for [%s]\u001b[0m\n".formatted(toolName));
        onText.accept("  \u001b[2m%s\u001b[0m\n".formatted(description));

        return confirmationHandler.askUser(toolName, description);
    }
}
