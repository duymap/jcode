package com.jcode.tui;

import com.jcode.AgentSession;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;

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
            }
            if (modelFallbackMessage != null) {
                out.println("  \u001b[33m" + modelFallbackMessage + "\u001b[0m");
            }
            out.println("  \u001b[2mType a message to start. Use /exit to quit, /clear to reset.\u001b[0m");
            out.println();
            out.flush();

            // Slash command completer — shows menu when user types "/"
            Completer slashCompleter = (reader, line, candidates) -> {
                String buf = line.line();
                if (buf.startsWith("/")) {
                    String prefix = buf.trim();
                    String[][] commands = {
                        {"/help",  "Show available commands"},
                        {"/clear", "Clear conversation and start fresh"},
                        {"/exit",  "Exit jcode"},
                    };
                    for (String[] entry : commands) {
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

                // Handle commands
                if ("/exit".equals(trimmed) || "/quit".equals(trimmed)) {
                    out.println("  Goodbye!");
                    out.flush();
                    break;
                }
                if ("/clear".equals(trimmed)) {
                    session.clearHistory();
                    out.println("  \u001b[2mConversation cleared.\u001b[0m");
                    out.println();
                    out.flush();
                    continue;
                }
                if ("/help".equals(trimmed)) {
                    out.println();
                    out.println("  \u001b[1mCommands:\u001b[0m");
                    out.println("    /exit   - Exit jcode");
                    out.println("    /clear  - Clear conversation and start fresh");
                    out.println("    /help   - Show this help");
                    out.println();
                    out.flush();
                    continue;
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
}
