package com.jcode.tui;

import com.jcode.AgentSession;
import com.jcode.MemoryManager;
import com.jcode.ModelResolver;
import com.jcode.PermissionManager;
import com.jcode.model.Model;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Terminal UI application runner - supports interactive REPL and one-shot print mode.
 */
public class AppRunner {

    private static final String BANNER = """

              \u001b[1;91m    ██\u001b[0m  \u001b[93m ████ \u001b[0m  \u001b[92m ████ \u001b[0m  \u001b[1;96m   ██\u001b[0m  \u001b[94m ████ \u001b[0m
              \u001b[1;91m    ██\u001b[0m  \u001b[93m██    \u001b[0m  \u001b[92m██  ██\u001b[0m  \u001b[1;96m   ██\u001b[0m  \u001b[94m██  ██\u001b[0m
              \u001b[1;91m    ██\u001b[0m  \u001b[93m██    \u001b[0m  \u001b[92m██  ██\u001b[0m  \u001b[1;96m █████\u001b[0m  \u001b[94m████  \u001b[0m
              \u001b[1;91m██  ██\u001b[0m  \u001b[93m██    \u001b[0m  \u001b[92m██  ██\u001b[0m  \u001b[1;96m██  ██\u001b[0m  \u001b[94m██    \u001b[0m
              \u001b[1;91m ████ \u001b[0m  \u001b[93m ████ \u001b[0m  \u001b[92m ████ \u001b[0m  \u001b[1;96m █████\u001b[0m  \u001b[94m ████ \u001b[0m
            """;

    private static final String PROMPT = "\u001b[1;36mjcode>\u001b[0m ";

    private static final String[][] COMMANDS = {
            {"/help",    "Show available commands"},
            {"/clear",   "Clear conversation and start fresh"},
            {"/compact", "Compress conversation history to save context"},
            {"/commit",  "Generate a commit message and commit staged changes"},
            {"/diff",    "Show current git diff"},
            {"/model",   "Show or switch the active model"},
            {"/memory",  "List stored memories (or /memory <query> to search)"},
            {"/cost",    "Show estimated token usage for this session"},
            {"/exit",    "Exit jcode"},
    };

    /**
     * Run the app in either interactive or one-shot print mode.
     */
    public static void run(AgentSession session, String printPrompt, String modelFallbackMessage)
            throws Exception {

        if (printPrompt != null) {
            runPrintMode(session, printPrompt);
            return;
        }

        runInteractive(session, modelFallbackMessage);
    }

    /**
     * One-shot mode: send prompt, print response, exit.
     */
    private static void runPrintMode(AgentSession session, String prompt) throws IOException {
        Spinner spinner = new Spinner(new PrintWriter(System.out, true));
        spinner.start();
        String response = session.chat(prompt, text -> {
            spinner.stop();
            System.out.print(text);
        });
        spinner.stop();
        System.out.println();
    }

    /**
     * Interactive REPL mode with JLine.
     */
    private static void runInteractive(AgentSession session, String modelFallbackMessage)
            throws Exception {

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            PrintWriter out = terminal.writer();

            // Print banner
            out.print(BANNER);
            out.println();
            out.println("  \u001b[2mModel: " + session.getModel().id() + "\u001b[0m");
            if (session.isReadonly()) {
                out.println("  \u001b[33mMode: read-only (no write/bash tools)\u001b[0m");
            } else {
                String modeLabel = switch (session.getPermissionMode()) {
                    case AUTO -> "auto (asks for dangerous commands)";
                    case DEFAULT -> "default (asks for all writes)";
                    case BYPASS -> "bypass (no confirmations)";
                };
                out.println("  \u001b[2mPermissions: " + modeLabel + "\u001b[0m");
            }
            if (modelFallbackMessage != null) {
                out.println("  \u001b[33m" + modelFallbackMessage + "\u001b[0m");
            }
            out.println("  \u001b[2mType a message to start. Use /help for commands, /exit to quit.\u001b[0m");
            out.println();
            out.flush();

            // Slash command completer — shows menu when user types "/"
            Completer slashCompleter = (reader, line, candidates) -> {
                String buf = line.line();
                if (buf.startsWith("/")) {
                    String prefix = buf.trim();
                    for (String[] entry : COMMANDS) {
                        if (entry[0].startsWith(prefix)) {
                            candidates.add(new Candidate(
                                entry[0], entry[0], null, entry[1], null, null, true));
                        }
                    }
                }
            };

            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(slashCompleter)
                    .option(LineReader.Option.AUTO_LIST, true)
                    .option(LineReader.Option.LIST_AMBIGUOUS, true)
                    .option(LineReader.Option.AUTO_MENU, true)
                    .option(LineReader.Option.LIST_PACKED, false)
                    .option(LineReader.Option.AUTO_MENU_LIST, true)
                    .option(LineReader.Option.GROUP_PERSIST, true)
                    .build();

            // Style: remove bright-magenta background from completion menu
            lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_BACKGROUND, "bg:default");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_BACKGROUND, "bg:default");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_SELECTION, "fg:cyan,bold");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_SELECTION, "fg:cyan,bold");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_GROUP, "fg:white,bold");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_GROUP, "fg:white,bold");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_DESCRIPTION, "fg:bright-black");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_DESCRIPTION, "fg:bright-black");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_LIST_STARTING, "fg:cyan");
            lineReader.setVariable(LineReader.COMPLETION_STYLE_STARTING, "fg:cyan");

            // Auto-trigger completion when "/" is typed as the first character
            lineReader.setAutosuggestion(LineReader.SuggestionType.COMPLETER);

            // Set up permission confirmation handler using the terminal
            session.getPermissionManager().setConfirmationHandler((toolName, description) -> {
                try {
                    out.print("  Allow? [\u001b[32my\u001b[0m/\u001b[31mn\u001b[0m] ");
                    out.flush();
                    String input = lineReader.readLine("").trim().toLowerCase();
                    if (input.equals("y") || input.equals("yes")) {
                        return true;
                    }
                    out.println("  \u001b[31mDenied.\u001b[0m");
                    out.flush();
                    return false;
                } catch (Exception e) {
                    out.println("  \u001b[31mDenied (input error).\u001b[0m");
                    out.flush();
                    return false;
                }
            });

            while (true) {
                String input;
                try {
                    input = lineReader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (input == null || input.isBlank()) continue;

                String trimmed = input.trim();

                // Handle slash commands
                if (trimmed.startsWith("/")) {
                    boolean handled = handleCommand(trimmed, session, out);
                    if (handled) {
                        if ("/exit".equals(trimmed) || "/quit".equals(trimmed)) break;
                        continue;
                    }
                }

                // Chat with the agent
                out.println();
                out.flush();

                try {
                    Spinner spinner = new Spinner(out);
                    spinner.start();
                    session.chat(trimmed, text -> {
                        spinner.stop();
                        out.print(text);
                        out.flush();
                    });
                    spinner.stop();
                    out.println();
                    out.println();
                    out.flush();
                } catch (Exception e) {
                    out.println("\n  \u001b[31mError: " + e.getMessage() + "\u001b[0m\n");
                    out.flush();
                }
            }
        }
    }

    /**
     * Handle a slash command. Returns true if handled (including /exit).
     */
    private static boolean handleCommand(String command, AgentSession session, PrintWriter out) {
        // Parse command and args
        String cmd = command.split("\\s+")[0];
        String args = command.length() > cmd.length() ? command.substring(cmd.length()).trim() : "";

        switch (cmd) {
            case "/exit", "/quit" -> {
                out.println("  Goodbye!");
                out.flush();
                return true;
            }
            case "/clear" -> {
                session.clearHistory();
                out.println("  \u001b[2mConversation cleared.\u001b[0m\n");
                out.flush();
                return true;
            }
            case "/help" -> {
                out.println();
                out.println("  \u001b[1mCommands:\u001b[0m");
                for (String[] entry : COMMANDS) {
                    out.printf("    %-10s - %s%n", entry[0], entry[1]);
                }
                out.println();
                out.flush();
                return true;
            }
            case "/compact" -> {
                handleCompact(session, out);
                return true;
            }
            case "/commit" -> {
                handleCommit(session, out);
                return true;
            }
            case "/diff" -> {
                handleDiff(session, out);
                return true;
            }
            case "/model" -> {
                handleModel(session, args, out);
                return true;
            }
            case "/memory" -> {
                handleMemory(session, args, out);
                return true;
            }
            case "/cost" -> {
                handleCost(session, out);
                return true;
            }
            default -> {
                out.println("  \u001b[33mUnknown command: " + cmd + ". Type /help for available commands.\u001b[0m\n");
                out.flush();
                return true;
            }
        }
    }

    private static void handleCompact(AgentSession session, PrintWriter out) {
        out.println();
        try {
            Spinner spinner = new Spinner(out);
            spinner.start();
            String result = session.compactHistory(text -> {
                spinner.stop();
                // Don't print summary text to user, it's internal
            });
            spinner.stop();
            out.println("  \u001b[2m" + result + "\u001b[0m");
        } catch (Exception e) {
            out.println("  \u001b[31mCompact failed: " + e.getMessage() + "\u001b[0m");
        }
        out.println();
        out.flush();
    }

    private static void handleCommit(AgentSession session, PrintWriter out) {
        out.println();
        if (session.isReadonly()) {
            out.println("  \u001b[33mCannot commit in read-only mode.\u001b[0m\n");
            out.flush();
            return;
        }
        try {
            // Ask the LLM to generate commit message and commit
            Spinner spinner = new Spinner(out);
            spinner.start();
            session.chat(
                    "Look at the current git diff (staged and unstaged changes) using bash. " +
                    "Generate a concise, conventional commit message. Then stage all changes " +
                    "and create the commit. Show me the commit message before committing.",
                    text -> {
                        spinner.stop();
                        out.print(text);
                        out.flush();
                    });
            spinner.stop();
            out.println();
        } catch (Exception e) {
            out.println("  \u001b[31mCommit failed: " + e.getMessage() + "\u001b[0m");
        }
        out.println();
        out.flush();
    }

    private static void handleDiff(AgentSession session, PrintWriter out) {
        out.println();
        String diff = runShellCommand("git diff", session.getCwd());
        String staged = runShellCommand("git diff --cached", session.getCwd());

        if ((diff == null || diff.isEmpty()) && (staged == null || staged.isEmpty())) {
            out.println("  \u001b[2mNo changes detected.\u001b[0m");
        } else {
            if (staged != null && !staged.isEmpty()) {
                out.println("  \u001b[1;32mStaged changes:\u001b[0m");
                printDiffOutput(staged, out);
            }
            if (diff != null && !diff.isEmpty()) {
                out.println("  \u001b[1;33mUnstaged changes:\u001b[0m");
                printDiffOutput(diff, out);
            }
        }
        out.println();
        out.flush();
    }

    private static void printDiffOutput(String diff, PrintWriter out) {
        String[] lines = diff.split("\n");
        int show = Math.min(lines.length, 50);
        for (int i = 0; i < show; i++) {
            String line = lines[i];
            if (line.startsWith("+") && !line.startsWith("+++")) {
                out.println("  \u001b[32m" + line + "\u001b[0m");
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                out.println("  \u001b[31m" + line + "\u001b[0m");
            } else if (line.startsWith("@@")) {
                out.println("  \u001b[36m" + line + "\u001b[0m");
            } else {
                out.println("  \u001b[2m" + line + "\u001b[0m");
            }
        }
        if (lines.length > 50) {
            out.println("  \u001b[2m... (" + lines.length + " lines total)\u001b[0m");
        }
    }

    private static void handleModel(AgentSession session, String args, PrintWriter out) {
        out.println();
        if (args.isEmpty()) {
            // Show current model
            Model m = session.getModel();
            out.println("  \u001b[1mCurrent model:\u001b[0m " + m.id());
            out.println("  \u001b[2mProvider: " + m.provider() + "\u001b[0m");
            out.println("  \u001b[2mContext window: " + formatNumber(m.contextWindow()) + " tokens\u001b[0m");
            out.println("  \u001b[2mMax output: " + formatNumber(m.maxTokens()) + " tokens\u001b[0m");
        } else {
            // Switch model
            try {
                Model newModel = ModelResolver.resolveModel(
                        session.getModel().provider(), args, session.getModel().baseUrl());
                session.setModel(newModel);
                out.println("  \u001b[32mSwitched to model: " + newModel.id() + "\u001b[0m");
            } catch (Exception e) {
                out.println("  \u001b[31mFailed to switch model: " + e.getMessage() + "\u001b[0m");
            }
        }
        out.println();
        out.flush();
    }

    private static void handleMemory(AgentSession session, String args, PrintWriter out) {
        out.println();
        MemoryManager mm = session.getMemoryManager();
        if (args.isEmpty()) {
            // List all memories
            String result = mm.listMemories();
            out.println("  " + result.replace("\n", "\n  "));
        } else {
            // Search memories
            String result = mm.recallMemories(args);
            out.println("  " + result.replace("\n", "\n  "));
        }
        out.println();
        out.flush();
    }

    private static void handleCost(AgentSession session, PrintWriter out) {
        out.println();
        int inputTokens = session.getTotalInputTokens();
        int outputTokens = session.getTotalOutputTokens();
        int totalTokens = inputTokens + outputTokens;

        out.println("  \u001b[1mSession Token Usage (estimated):\u001b[0m");
        out.println("    Input:    " + formatNumber(inputTokens) + " tokens");
        out.println("    Output:   " + formatNumber(outputTokens) + " tokens");
        out.println("    Total:    " + formatNumber(totalTokens) + " tokens");
        out.println("    Messages: " + session.getMessageCount());
        out.println();
        out.flush();
    }

    private static String formatNumber(int n) {
        if (n >= 1_000_000) return "%.1fM".formatted(n / 1_000_000.0);
        if (n >= 1_000) return "%.1fK".formatted(n / 1_000.0);
        return String.valueOf(n);
    }

    private static String runShellCommand(String command, String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(Path.of(cwd).toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                output = sb.toString();
            }

            process.waitFor(10, TimeUnit.SECONDS);
            return output.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
