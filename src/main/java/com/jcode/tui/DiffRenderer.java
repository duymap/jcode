package com.jcode.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders colored ANSI diff output for file edits and writes.
 */
public class DiffRenderer {

    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String DIM = "\033[2m";
    private static final String RED_BG = "\033[41m\033[37m";
    private static final String GREEN_BG = "\033[42m\033[30m";

    private static final int CONTEXT_LINES = 3;

    /**
     * Render a diff between old and new text for a specific region of a file.
     *
     * @param filePath    display path for the header
     * @param oldText     the original text that was replaced
     * @param newText     the replacement text
     * @param startLine   1-based line number where the change starts in the file
     * @return ANSI-colored diff string
     */
    public static String render(String filePath, String oldText, String newText, int startLine) {
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);

        int added = newLines.length - oldLines.length;
        int addedCount = 0;
        int removedCount = 0;

        // Simple line-by-line diff
        List<DiffLine> diffLines = computeDiff(oldLines, newLines, startLine);

        for (DiffLine dl : diffLines) {
            if (dl.type == LineType.ADDED) addedCount++;
            if (dl.type == LineType.REMOVED) removedCount++;
        }

        StringBuilder sb = new StringBuilder();

        // Summary line
        sb.append(DIM);
        sb.append("Added ").append(addedCount).append(addedCount == 1 ? " line" : " lines");
        sb.append(", removed ").append(removedCount).append(removedCount == 1 ? " line" : " lines");
        sb.append(RESET).append("\n");

        // Find the range with context
        int firstChange = -1;
        int lastChange = -1;
        for (int i = 0; i < diffLines.size(); i++) {
            if (diffLines.get(i).type != LineType.CONTEXT) {
                if (firstChange == -1) firstChange = i;
                lastChange = i;
            }
        }

        if (firstChange == -1) {
            return sb.toString();
        }

        int displayStart = Math.max(0, firstChange - CONTEXT_LINES);
        int displayEnd = Math.min(diffLines.size() - 1, lastChange + CONTEXT_LINES);

        // Determine max line number width for padding
        int maxLineNum = 0;
        for (int i = displayStart; i <= displayEnd; i++) {
            maxLineNum = Math.max(maxLineNum, diffLines.get(i).lineNum);
        }
        int numWidth = Math.max(3, String.valueOf(maxLineNum).length());

        for (int i = displayStart; i <= displayEnd; i++) {
            DiffLine dl = diffLines.get(i);
            String lineNumStr = String.format("%" + numWidth + "d", dl.lineNum);

            switch (dl.type) {
                case CONTEXT -> sb.append(DIM)
                        .append("  ").append(lineNumStr).append("  ").append(dl.text)
                        .append(RESET).append("\n");
                case REMOVED -> sb.append(RED_BG)
                        .append("  ").append(lineNumStr).append(" -").append(dl.text)
                        .append(RESET).append("\n");
                case ADDED -> sb.append(GREEN_BG)
                        .append("  ").append(lineNumStr).append(" +").append(dl.text)
                        .append(RESET).append("\n");
            }
        }

        // Ellipsis if there are more lines after
        if (displayEnd < diffLines.size() - 1) {
            sb.append(DIM).append("  ").append(" ".repeat(numWidth)).append("  ...").append(RESET).append("\n");
        }

        return sb.toString();
    }

    /**
     * Render a summary for a newly created file.
     */
    public static String renderNewFile(String filePath, String content) {
        int lineCount = content.split("\n", -1).length;
        long bytes = content.getBytes().length;
        StringBuilder sb = new StringBuilder();
        sb.append(DIM).append(lineCount).append(lineCount == 1 ? " line" : " lines")
                .append(", ").append(bytes).append(" bytes");
        sb.append(RESET).append("\n");
        return sb.toString();
    }

    private static List<DiffLine> computeDiff(String[] oldLines, String[] newLines, int startLine) {
        List<DiffLine> result = new ArrayList<>();

        // Use a simple LCS-based approach for better diffs
        int[][] lcs = new int[oldLines.length + 1][newLines.length + 1];
        for (int i = oldLines.length - 1; i >= 0; i--) {
            for (int j = newLines.length - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        int i = 0, j = 0;
        int oldLineNum = startLine;
        int newLineNum = startLine;

        while (i < oldLines.length || j < newLines.length) {
            if (i < oldLines.length && j < newLines.length && oldLines[i].equals(newLines[j])) {
                result.add(new DiffLine(LineType.CONTEXT, newLineNum, oldLines[i]));
                i++;
                j++;
                oldLineNum++;
                newLineNum++;
            } else if (i < oldLines.length && (j >= newLines.length || lcs[i + 1][j] >= lcs[i][j + 1])) {
                result.add(new DiffLine(LineType.REMOVED, oldLineNum, oldLines[i]));
                i++;
                oldLineNum++;
            } else {
                result.add(new DiffLine(LineType.ADDED, newLineNum, newLines[j]));
                j++;
                newLineNum++;
            }
        }

        return result;
    }

    private enum LineType { CONTEXT, ADDED, REMOVED }

    private record DiffLine(LineType type, int lineNum, String text) {}
}
