package com.jcode.tui;

import com.jcode.AgentSession;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
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

            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();

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
