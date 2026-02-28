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
            "Crafting response",
            "Working on it",
            "Figuring it out",
            "Crunching ideas",
            "Brewing thoughts",
    };

    private final PrintWriter out;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public Spinner(PrintWriter out) {
        this.out = out;
    }

    /**
     * Start the spinner animation in a background thread.
     */
    public void start() {
        if (running.getAndSet(true)) return;

        String wording = WORDINGS[ThreadLocalRandom.current().nextInt(WORDINGS.length)];
        thread = new Thread(() -> {
            int frame = 0;
            boolean blink = true;
            try {
                while (running.get()) {
                    String spinner = FRAMES[frame % FRAMES.length];
                    // Blink effect: alternate between dim and normal
                    String style = blink ? "\u001b[1;35m" : "\u001b[2;35m";
                    String line = "  " + style + spinner + " " + wording + "...\u001b[0m";

                    // Write spinner line then move cursor back to start of line
                    out.print("\r" + line + "\u001b[K");
                    out.flush();

                    frame++;
                    if (frame % 3 == 0) blink = !blink;
                    Thread.sleep(80);
                }
            } catch (InterruptedException ignored) {
                // Expected on stop
            } finally {
                // Clear the spinner line
                out.print("\r\u001b[K");
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
