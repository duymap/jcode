package com.jcode.tui;

import java.io.PrintWriter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Animated spinner with random wording, shown while waiting for LLM response.
 */
public class Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private static final String[] WORDINGS = {
            "Thinking",
            "Pondering",
            "Reasoning",
            "Analyzing",
            "Processing",
            "Contemplating",
            "Reflecting",
            "Evaluating",
            "Computing",
            "Synthesizing",
            "Considering",
            "Investigating",
            "Examining",
            "Exploring",
            "Decoding",
            "Interpreting",
            "Assembling",
            "Composing",
            "Formulating",
            "Deliberating",
            "Weighing",
            "Digesting",
            "Untangling",
            "Mapping",
            "Structuring",
            "Parsing",
            "Resolving",
            "Iterating",
            "Refining",
            "Deducing",
            "Inferring",
            "Connecting",
            "Unraveling",
            "Distilling",
            "Calculating",
            "Organizing",
            "Strategizing",
            "Brainstorming",
            "Deciphering",
            "Visualizing",
            "Imagining",
            "Sculpting",
            "Architecting",
            "Calibrating",
            "Orchestrating",
            "Navigating",
            "Compiling",
            "Abstracting",
            "Harmonizing",
            "Crystallizing",
    };

    private final PrintWriter out;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private boolean inline;
    private Thread thread;

    public Spinner(PrintWriter out) {
        this.out = out;
    }

    /**
     * Start the spinner animation in a background thread with a random wording.
     * Uses its own line (carriage-return based).
     */
    public void start() {
        this.inline = false;
        startInternal(WORDINGS[ThreadLocalRandom.current().nextInt(WORDINGS.length)]);
    }

    /**
     * Start the spinner inline on the current line (appends after existing text).
     * Uses ANSI save/restore cursor so the tool header line is preserved.
     */
    public void startInline(String label) {
        this.inline = true;
        startInternal(label);
    }

    private void startInternal(String label) {
        if (running.getAndSet(true)) return;

        thread = new Thread(() -> {
            int frame = 0;
            boolean blink = true;
            try {
                while (running.get()) {
                    String spinner = FRAMES[frame % FRAMES.length];
                    String style = blink ? "\u001b[1;35m" : "\u001b[2;35m";
                    String text = style + spinner + " " + label + "...\u001b[0m";

                    if (inline) {
                        // Save cursor, print spinner, then restore cursor
                        out.print("\u001b[s" + text + "\u001b[u");
                    } else {
                        out.print("\r  " + text + "\u001b[K");
                    }
                    out.flush();

                    frame++;
                    if (frame % 3 == 0) blink = !blink;
                    Thread.sleep(80);
                }
            } catch (InterruptedException ignored) {
                // Expected on stop
            } finally {
                if (inline) {
                    // Restore cursor and clear to end of line
                    out.print("\u001b[s\u001b[K\u001b[u");
                } else {
                    out.print("\r\u001b[K");
                }
                out.flush();
            }
        }, "jcode-spinner");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stop the spinner and clear the line.
     */
    public void stop() {
        if (!running.getAndSet(false)) return;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(200);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
