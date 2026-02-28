package com.jcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jcode.extensions.PlanningExtension;
import com.jcode.model.Model;
import com.jcode.model.ReasoningModelConfig;
import com.jcode.tools.*;
import com.jcode.tui.Spinner;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Core agent session: manages the LLM conversation loop with tool calling.
 */
public class AgentSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build();

    private static final String SYSTEM_PROMPT = """
            You are jcode, a helpful AI coding assistant. You help users with software engineering tasks \
            including writing code, debugging, refactoring, and explaining code.

            You have access to tools for reading, writing, and editing files, running bash commands, \
            searching files with grep, and finding files by pattern.

            Always use tools to interact with the filesystem. Read files before editing them. \
            Be concise in your responses. Focus on solving the user's problem efficiently.

            When working on code:
            - Read relevant files first to understand the codebase
            - Make targeted, minimal changes
            - Explain what you're doing briefly
            - Use bash for running tests, git commands, builds, etc.""";

    private final Model model;
    private final String cwd;
    private final List<Tool> tools;
    private final List<Map<String, Object>> messages;
    private final PlanningExtension planningExtension;
    private final boolean readonly;

    public AgentSession(Model model, String cwd, boolean readonly,
                        boolean disablePlanning, ReasoningModelConfig reasoningModel) {
        this.model = model;
        this.cwd = cwd;
        this.readonly = readonly;
        this.messages = new ArrayList<>();

        // Register tools
        this.tools = new ArrayList<>();
        this.tools.add(new ReadFileTool());
        this.tools.add(new GrepTool());
        this.tools.add(new FindFilesTool());
        if (!readonly) {
            this.tools.add(new WriteFileTool());
            this.tools.add(new EditFileTool());
            this.tools.add(new BashTool());
        }

        // Planning
        if (!disablePlanning && reasoningModel != null) {
            this.planningExtension = new PlanningExtension(reasoningModel);
        } else {
            this.planningExtension = null;
        }
    }

    public Model getModel() {
        return model;
    }

    public boolean isReadonly() {
        return readonly;
    }

    /**
     * Send a user message and get the assistant's response, executing any tool calls.
     * Streams assistant text to the callback as it arrives.
     *
     * @param userMessage the user's input
     * @param onText      callback for streaming assistant text chunks
     * @return the final complete assistant response text
     */
    public String chat(String userMessage, TextCallback onText) throws IOException {
        // Planning step
        String messageToSend = userMessage;
        if (planningExtension != null) {
            String plan = planningExtension.generatePlan(userMessage);
            if (plan != null) {
                String planDisplay = PlanningExtension.formatPlanDisplay(plan);
                onText.onText(planDisplay + "\n");
                messageToSend = PlanningExtension.augmentWithPlan(userMessage, plan);
            }
        }

        messages.add(Map.of("role", "user", "content", messageToSend));

        // Agent loop: call LLM, execute tools, repeat until no more tool calls
        while (true) {
            JsonNode response = callLlm(onText);

            // Check for tool calls
            JsonNode toolCalls = response.path("choices").path(0).path("message").path("tool_calls");
            String content = response.path("choices").path(0).path("message").path("content").asText("");

            // Add assistant message to history
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            if (!content.isEmpty()) {
                assistantMsg.put("content", content);
            }
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                List<Map<String, Object>> tcList = new ArrayList<>();
                for (JsonNode tc : toolCalls) {
                    Map<String, Object> tcMap = new LinkedHashMap<>();
                    tcMap.put("id", tc.get("id").asText());
                    tcMap.put("type", "function");
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.path("function").path("name").asText());
                    fn.put("arguments", tc.path("function").path("arguments").asText());
                    tcMap.put("function", fn);
                    tcList.add(tcMap);
                }
                assistantMsg.put("tool_calls", tcList);
            }
            messages.add(assistantMsg);

            // If no tool calls, we're done
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                return content;
            }

            // Execute tool calls
            for (JsonNode tc : toolCalls) {
                String toolCallId = tc.get("id").asText();
                String toolName = tc.path("function").path("name").asText();
                String argsStr = tc.path("function").path("arguments").asText("{}");

                onText.onText("\n\u001b[33m[%s]\u001b[0m ".formatted(toolName));

                String result;
                Spinner toolSpinner = new Spinner(new PrintWriter(System.out, true));
                try {
                    JsonNode toolArgs = MAPPER.readTree(argsStr);
                    Tool tool = findTool(toolName);
                    if (tool == null) {
                        result = "Error: Unknown tool: " + toolName;
                    } else {
                        // Show key args
                        showToolArgs(toolName, toolArgs, onText);
                        // Start inline spinner (appends after args, cleans up on stop)
                        String spinnerLabel = buildToolSpinnerLabel(toolName, toolArgs);
                        toolSpinner.startInline(spinnerLabel);
                        result = tool.execute(toolArgs, cwd);
                    }
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                } finally {
                    toolSpinner.stop();
                }

                // Split off diff display if present
                String diffDisplay = null;
                if (result.contains("@@DIFF@@")) {
                    int sep = result.indexOf("@@DIFF@@");
                    diffDisplay = result.substring(sep + "@@DIFF@@".length());
                    result = result.substring(0, sep);
                }

                // Truncate very long results
                if (result.length() > 50_000) {
                    result = result.substring(0, 50_000) + "\n[Output truncated at 50KB]";
                }

                if (diffDisplay != null) {
                    onText.onText(diffDisplay.stripTrailing() + "\n");
                } else if ("bash".equals(toolName)) {
                    onText.onText(formatBashPreview(result) + "\n");
                } else {
                    onText.onText("\u001b[2m(%d chars)\u001b[0m\n".formatted(result.length()));
                }

                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", toolCallId,
                        "content", result
                ));
            }
        }
    }

    private void showToolArgs(String toolName, JsonNode args, TextCallback onText) {
        switch (toolName) {
            case "read", "write", "edit" -> {
                if (args.has("path")) onText.onText(args.get("path").asText() + " ");
            }
            case "bash" -> {
                if (args.has("command")) {
                    String cmd = args.get("command").asText();
                    if (cmd.length() > 80) cmd = cmd.substring(0, 77) + "...";
                    onText.onText(cmd + " ");
                }
            }
            case "grep" -> {
                if (args.has("pattern")) onText.onText("'" + args.get("pattern").asText() + "' ");
            }
            case "find" -> {
                if (args.has("pattern")) onText.onText(args.get("pattern").asText() + " ");
            }
        }
    }

    private static final String DIM = "\u001b[2m";
    private static final String RESET = "\u001b[0m";
    private static final int PREVIEW_MAX_LINES = 4;
    private static final int PREVIEW_MAX_LINE_LEN = 120;

    private String formatBashPreview(String result) {
        if (result.isEmpty()) {
            return DIM + "(empty)" + RESET;
        }

        String[] lines = result.split("\n", -1);
        int totalLines = lines.length;

        // Strip trailing empty lines for display
        int end = totalLines;
        while (end > 0 && lines[end - 1].isBlank()) end--;

        if (end == 0) {
            return DIM + "(empty)" + RESET;
        }

        StringBuilder sb = new StringBuilder();
        int showCount = Math.min(end, PREVIEW_MAX_LINES);
        for (int i = 0; i < showCount; i++) {
            String line = lines[i];
            if (line.length() > PREVIEW_MAX_LINE_LEN) {
                line = line.substring(0, PREVIEW_MAX_LINE_LEN) + "…";
            }
            sb.append(DIM).append("  ").append(line).append(RESET).append("\n");
        }
        if (end > PREVIEW_MAX_LINES) {
            sb.append(DIM).append("  … (").append(end).append(" lines total)").append(RESET);
        }
        return sb.toString().stripTrailing();
    }

    private String buildToolSpinnerLabel(String toolName, JsonNode args) {
        return switch (toolName) {
            case "read" -> "Reading " + args.path("path").asText("file");
            case "write" -> "Writing " + args.path("path").asText("file");
            case "edit" -> "Editing " + args.path("path").asText("file");
            case "bash" -> {
                String cmd = args.path("command").asText("command");
                if (cmd.length() > 40) cmd = cmd.substring(0, 37) + "...";
                yield "Running " + cmd;
            }
            case "grep" -> "Searching '" + args.path("pattern").asText("") + "'";
            case "find" -> "Finding " + args.path("pattern").asText("files");
            default -> "Executing " + toolName;
        };
    }

    /**
     * Call the LLM API with the current message history.
     * Streams text content to onText callback, returns the full response JSON.
     */
    private JsonNode callLlm(TextCallback onText) throws IOException {
        // Build request
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("model", model.id());
        requestBody.put("max_tokens", model.maxTokens());
        requestBody.put("stream", true);

        // Messages
        ArrayNode messagesArray = requestBody.putArray("messages");

        // System message
        ObjectNode sysMsg = messagesArray.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", SYSTEM_PROMPT);

        // Conversation messages
        for (Map<String, Object> msg : messages) {
            messagesArray.add(MAPPER.valueToTree(msg));
        }

        // Tools
        ArrayNode toolsArray = requestBody.putArray("tools");
        for (Tool tool : tools) {
            ObjectNode toolObj = toolsArray.addObject();
            toolObj.put("type", "function");
            ObjectNode fn = toolObj.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", MAPPER.valueToTree(tool.parameterSchema()));
        }

        RequestBody body = RequestBody.create(
                MAPPER.writeValueAsString(requestBody),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(model.baseUrl() + "/chat/completions")
                .post(body)
                .build();

        // Stream response
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new IOException("LLM API error (HTTP %d): %s".formatted(response.code(), errorBody));
            }

            return processStream(response, onText);
        }
    }

    /**
     * Process SSE stream from the LLM API.
     * Accumulates content and tool calls from stream deltas.
     */
    private JsonNode processStream(Response response, TextCallback onText) throws IOException {
        StringBuilder fullContent = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCallMap = new LinkedHashMap<>();
        // Buffer for filtering <think>...</think> tags from streamed content
        StringBuilder thinkBuffer = new StringBuilder();
        boolean insideThink = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Objects.requireNonNull(response.body()).byteStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                try {
                    JsonNode chunk = MAPPER.readTree(data);
                    JsonNode delta = chunk.path("choices").path(0).path("delta");

                    // Content — filter out <think>...</think> blocks
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String text = delta.get("content").asText();
                        fullContent.append(text);

                        // Process text to strip <think> tags for display
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (insideThink) {
                                thinkBuffer.append(c);
                                if (thinkBuffer.toString().endsWith("</think>")) {
                                    insideThink = false;
                                    thinkBuffer.setLength(0);
                                }
                            } else {
                                thinkBuffer.append(c);
                                String buf = thinkBuffer.toString();
                                if (buf.equals("<think>")) {
                                    insideThink = true;
                                    thinkBuffer.setLength(0);
                                } else if ("<think>".startsWith(buf)) {
                                    // Partial match — keep buffering
                                } else {
                                    // No match — flush buffer to output
                                    onText.onText(buf);
                                    thinkBuffer.setLength(0);
                                }
                            }
                        }
                    }

                    // Tool calls
                    JsonNode tcDelta = delta.path("tool_calls");
                    if (tcDelta.isArray()) {
                        for (JsonNode tc : tcDelta) {
                            int index = tc.path("index").asInt(0);
                            ToolCallAccumulator acc = toolCallMap.computeIfAbsent(
                                    index, k -> new ToolCallAccumulator());

                            if (tc.has("id") && !tc.get("id").isNull()) {
                                acc.id = tc.get("id").asText();
                            }
                            if (tc.path("function").has("name")) {
                                acc.name = tc.path("function").get("name").asText();
                            }
                            if (tc.path("function").has("arguments")) {
                                acc.arguments.append(tc.path("function").get("arguments").asText(""));
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed chunks
                }
            }
        }

        // Flush any remaining buffered text that wasn't part of a <think> tag
        if (!insideThink && thinkBuffer.length() > 0) {
            onText.onText(thinkBuffer.toString());
        }

        // Build final response object
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode choice = result.putArray("choices").addObject();
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", fullContent.toString());

        if (!toolCallMap.isEmpty()) {
            ArrayNode toolCallsNode = message.putArray("tool_calls");
            for (ToolCallAccumulator acc : toolCallMap.values()) {
                ObjectNode tc = toolCallsNode.addObject();
                tc.put("id", acc.id != null ? acc.id : UUID.randomUUID().toString());
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", acc.name != null ? acc.name : "unknown");
                fn.put("arguments", acc.arguments.toString());
            }
        }

        return result;
    }

    private Tool findTool(String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /** Clear conversation history. */
    public void clearHistory() {
        messages.clear();
    }

    @FunctionalInterface
    public interface TextCallback {
        void onText(String text);
    }

    private static class ToolCallAccumulator {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
