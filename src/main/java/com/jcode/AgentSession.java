package com.jcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jcode.extensions.PlanningExtension;
import com.jcode.model.Model;
import com.jcode.model.ReasoningModelConfig;
import com.jcode.tools.*;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;

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
            searching files with grep, and finding files by pattern. \
            You can also search the web and fetch web pages to look up documentation, API references, \
            or any information you need. Use web_search when you don't know something, then web_fetch to read the relevant pages.

            Always use tools to interact with the filesystem. Read files before editing them. \
            Be concise in your responses. Focus on solving the user's problem efficiently.

            IMPORTANT: Never repeat or echo file contents in your response text. The user can already see \
            tool results. Instead, briefly summarize what you found or reference specific line numbers.

            When working on code:
            - Read relevant files first to understand the codebase
            - Make targeted, minimal changes
            - Explain what you're doing briefly, referencing file paths and line numbers
            - Use bash for running tests, git commands, builds, etc.

            If the user message contains a PLAN section (marked with [[jcode_plan]]), follow the plan step by step \
            using the available tools. Execute each step immediately — do not just describe what you would do.""";

    private Model model;
    private final String cwd;
    private final List<Tool> tools;
    private final List<Map<String, Object>> messages;
    private final PlanningExtension planningExtension;
    private final boolean readonly;
    private final String systemPrompt;
    private final MemoryManager memoryManager;
    private final PermissionManager permissionManager;
    private int totalInputTokens;
    private int totalOutputTokens;

    public AgentSession(Model model, String cwd, boolean readonly,
                        boolean disablePlanning, ReasoningModelConfig reasoningModel,
                        PermissionManager.Mode permissionMode) {
        this.model = model;
        this.cwd = cwd;
        this.readonly = readonly;
        this.messages = new ArrayList<>();
        this.totalInputTokens = 0;
        this.totalOutputTokens = 0;
        this.memoryManager = new MemoryManager(cwd);
        this.permissionManager = new PermissionManager(
                readonly ? PermissionManager.Mode.BYPASS : permissionMode);

        // Build system prompt with dynamic context + memories
        this.systemPrompt = buildSystemPrompt(cwd, memoryManager);

        // Register tools
        this.tools = new ArrayList<>();
        this.tools.add(new ReadFileTool());
        this.tools.add(new GrepTool());
        this.tools.add(new FindFilesTool());
        this.tools.add(new WebSearchTool());
        this.tools.add(new WebFetchTool());
        this.tools.add(new MemoryTool(memoryManager));
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

    private static String buildSystemPrompt(String cwd, MemoryManager memoryManager) {
        StringBuilder prompt = new StringBuilder(SYSTEM_PROMPT);

        ContextBuilder ctx = new ContextBuilder(cwd);
        String dynamicContext = ctx.build();
        if (dynamicContext != null) {
            prompt.append(dynamicContext);
        }

        // Inject persistent memories
        String memories = memoryManager.loadMemoriesForPrompt();
        if (memories != null) {
            prompt.append("\n\n## Persistent Memories\n\n");
            prompt.append("The following memories were saved from previous sessions. ");
            prompt.append("Use them to inform your responses. ");
            prompt.append("Use the 'memory' tool to save new memories when the user shares ");
            prompt.append("preferences, corrections, or important project context.\n\n");
            prompt.append(memories);
        }

        return prompt.toString();
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public PermissionManager.Mode getPermissionMode() {
        return permissionManager.getMode();
    }

    public Model getModel() {
        return model;
    }

    public boolean isReadonly() {
        return readonly;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public String getCwd() {
        return cwd;
    }

    public int getTotalInputTokens() {
        return totalInputTokens;
    }

    public int getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public int getMessageCount() {
        return messages.size();
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

            // Execute tool calls — read-only tools run concurrently, write tools sequentially
            executeToolCalls(toolCalls, onText);
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
            case "web_search" -> {
                if (args.has("query")) onText.onText("'" + args.get("query").asText() + "' ");
            }
            case "web_fetch" -> {
                if (args.has("url")) onText.onText(args.get("url").asText() + " ");
            }
            case "memory" -> {
                if (args.has("action")) onText.onText(args.get("action").asText() + " ");
                if (args.has("name")) onText.onText("'" + args.get("name").asText() + "' ");
            }
        }
    }

    private static final String DIM = "\u001b[2m";
    private static final String RESET = "\u001b[0m";
    private static final int BASH_PREVIEW_LINES = 4;
    private static final int READ_PREVIEW_LINES = 10;
    private static final int PREVIEW_MAX_LINE_LEN = 120;

    /**
     * Execute tool calls with concurrency: read-only tools run in parallel,
     * write tools run sequentially. Tools are batched by type — consecutive
     * read-only tools form a concurrent batch, any write tool flushes the batch.
     */
    private void executeToolCalls(JsonNode toolCalls, TextCallback onText) {
        // Parse all tool calls into a list
        List<ParsedToolCall> parsed = new ArrayList<>();
        for (JsonNode tc : toolCalls) {
            String id = tc.get("id").asText();
            String name = tc.path("function").path("name").asText();
            String argsStr = tc.path("function").path("arguments").asText("{}");
            Tool tool = findTool(name);
            parsed.add(new ParsedToolCall(id, name, argsStr, tool));
        }

        // Check if ALL tools in this batch are read-only
        boolean allReadOnly = parsed.stream().allMatch(
                p -> p.tool != null && p.tool.isReadOnly());

        if (allReadOnly && parsed.size() > 1) {
            // Execute all read-only tools concurrently using virtual threads
            executeConcurrently(parsed, onText);
        } else {
            // Execute sequentially (safe default for write tools or mixed batches)
            for (ParsedToolCall ptc : parsed) {
                executeSingleTool(ptc, onText);
            }
        }
    }

    private void executeConcurrently(List<ParsedToolCall> toolCalls, TextCallback onText) {
        // Show all tool names first
        for (ParsedToolCall ptc : toolCalls) {
            try {
                JsonNode toolArgs = MAPPER.readTree(ptc.argsStr);
                onText.onText("\n\u001b[33m[%s]\u001b[0m ".formatted(ptc.name));
                showToolArgs(ptc.name, toolArgs, onText);
            } catch (Exception e) {
                onText.onText("\n\u001b[33m[%s]\u001b[0m ".formatted(ptc.name));
            }
        }

        // Execute concurrently with virtual threads
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<String>> futures = new LinkedHashMap<>();
            for (ParsedToolCall ptc : toolCalls) {
                futures.put(ptc.id, executor.submit(() -> {
                    JsonNode toolArgs = MAPPER.readTree(ptc.argsStr);
                    if (ptc.tool == null) {
                        return "Error: Unknown tool: " + ptc.name;
                    }
                    return ptc.tool.execute(toolArgs, cwd);
                }));
            }

            // Collect results in order and add to messages
            for (ParsedToolCall ptc : toolCalls) {
                String result;
                try {
                    result = futures.get(ptc.id).get(120, TimeUnit.SECONDS);
                } catch (Exception e) {
                    result = "Error: " + e.getMessage();
                }
                result = processToolResult(result, ptc.name, onText);
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", ptc.id,
                        "content", result
                ));
            }
        }
    }

    private void executeSingleTool(ParsedToolCall ptc, TextCallback onText) {
        onText.onText("\n\u001b[33m[%s]\u001b[0m ".formatted(ptc.name));

        String result;
        try {
            JsonNode toolArgs = MAPPER.readTree(ptc.argsStr);
            if (ptc.tool == null) {
                result = "Error: Unknown tool: " + ptc.name;
            } else {
                showToolArgs(ptc.name, toolArgs, onText);
                // Check permission before execution
                if (!permissionManager.checkPermission(ptc.tool, toolArgs, onText::onText)) {
                    result = "Permission denied by user for tool: " + ptc.name;
                } else {
                    result = ptc.tool.execute(toolArgs, cwd);
                }
            }
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
        }

        result = processToolResult(result, ptc.name, onText);
        messages.add(Map.of(
                "role", "tool",
                "tool_call_id", ptc.id,
                "content", result
        ));
    }

    /**
     * Process a tool result: handle diffs, truncation, and display preview.
     * Returns the (possibly truncated) result string for the message history.
     */
    private String processToolResult(String result, String toolName, TextCallback onText) {
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
        } else if ("read".equals(toolName)) {
            onText.onText("\n");
        } else if ("web_search".equals(toolName) || "web_fetch".equals(toolName)) {
            onText.onText(formatPreview(result, READ_PREVIEW_LINES) + "\n");
        } else if ("grep".equals(toolName) || "find".equals(toolName)) {
            onText.onText(formatPreview(result, READ_PREVIEW_LINES) + "\n");
        } else {
            onText.onText("\u001b[2m(%d chars)\u001b[0m\n".formatted(result.length()));
        }

        return result;
    }

    private record ParsedToolCall(String id, String name, String argsStr, Tool tool) {}

    private String formatPreview(String result, int maxLines) {
        if (result.isEmpty()) {
            return DIM + "(empty)" + RESET;
        }

        String[] lines = result.split("\n", -1);

        // Strip trailing empty lines for display
        int end = lines.length;
        while (end > 0 && lines[end - 1].isBlank()) end--;

        if (end == 0) {
            return DIM + "(empty)" + RESET;
        }

        StringBuilder sb = new StringBuilder();
        int showCount = Math.min(end, maxLines);
        for (int i = 0; i < showCount; i++) {
            String line = lines[i];
            if (line.length() > PREVIEW_MAX_LINE_LEN) {
                line = line.substring(0, PREVIEW_MAX_LINE_LEN) + "…";
            }
            sb.append(DIM).append("  ").append(line).append(RESET).append("\n");
        }
        if (end > maxLines) {
            sb.append(DIM).append("  … (").append(end).append(" lines total)").append(RESET);
        }
        return sb.toString().stripTrailing();
    }

    private String formatBashPreview(String result) {
        return formatPreview(result, BASH_PREVIEW_LINES);
    }


    /**
     * Estimate token count for a message (~4 chars per token).
     */
    private int estimateTokens(Map<String, Object> msg) {
        int chars = 0;
        Object content = msg.get("content");
        if (content != null) {
            chars += content.toString().length();
        }
        Object toolCalls = msg.get("tool_calls");
        if (toolCalls instanceof List<?> list) {
            chars += MAPPER.valueToTree(list).toString().length();
        }
        return Math.max(1, chars / 4);
    }

    private int estimateSystemTokens() {
        return systemPrompt.length() / 4;
    }

    private boolean autoCompacting = false;

    /**
     * Manage context window: auto-compact at 80% usage, hard-trim as last resort.
     * Called before each LLM call.
     */
    private void trimMessages() {
        int reserveForResponse = model.maxTokens();
        int systemTokens = estimateSystemTokens();
        int totalCapacity = (int) (model.contextWindow() * 0.9) - systemTokens - reserveForResponse;

        if (totalCapacity <= 0) return;

        int totalTokens = 0;
        for (Map<String, Object> msg : messages) {
            totalTokens += estimateTokens(msg);
        }

        if (totalTokens <= totalCapacity) return;

        // Try auto-compact first (LLM-powered summarization) — but prevent re-entrancy
        if (!autoCompacting && messages.size() > 4) {
            autoCompacting = true;
            try {
                autoCompact(totalTokens, totalCapacity);
                // Recalculate after compaction
                totalTokens = 0;
                for (Map<String, Object> msg : messages) {
                    totalTokens += estimateTokens(msg);
                }
                if (totalTokens <= totalCapacity) return;
            } catch (Exception e) {
                // Auto-compact failed, fall through to hard trim
            } finally {
                autoCompacting = false;
            }
        }

        // Last resort: hard trim oldest messages
        hardTrimMessages(totalTokens, totalCapacity);
    }

    /**
     * Auto-compact: summarize the oldest half of messages via LLM, replace with summary.
     */
    private void autoCompact(int totalTokens, int targetCapacity) throws IOException {
        // Find the midpoint — summarize the older half
        int halfTokens = totalTokens / 2;
        int accumulated = 0;
        int splitAt = 0;
        for (int i = 0; i < messages.size(); i++) {
            accumulated += estimateTokens(messages.get(i));
            splitAt = i + 1;
            if (accumulated >= halfTokens) break;
        }

        // Extend splitAt to avoid breaking tool_call/tool_result pairs
        while (splitAt < messages.size()) {
            Map<String, Object> next = messages.get(splitAt);
            if ("tool".equals(next.get("role"))) {
                splitAt++;
            } else if ("assistant".equals(next.get("role")) && next.containsKey("tool_calls")) {
                splitAt++;
            } else {
                break;
            }
        }

        if (splitAt <= 1 || splitAt >= messages.size()) return;

        // Build a summary of the old messages
        StringBuilder oldContext = new StringBuilder();
        for (int i = 0; i < splitAt; i++) {
            Map<String, Object> msg = messages.get(i);
            String role = String.valueOf(msg.get("role"));
            Object content = msg.get("content");
            if (content != null && !content.toString().isEmpty()) {
                String text = content.toString();
                // Truncate individual messages in summary input to keep it reasonable
                if (text.length() > 500) text = text.substring(0, 500) + "...";
                oldContext.append(role).append(": ").append(text).append("\n");
            }
        }

        // Ask LLM to summarize (using a minimal temporary conversation)
        List<Map<String, Object>> savedMessages = new ArrayList<>(messages);
        messages.clear();
        messages.add(Map.of("role", "user", "content",
                "Summarize this conversation history in ~200 words, preserving key decisions, " +
                        "file paths, and important context. Be concise.\n\n" + oldContext));

        try {
            // Silent callback — don't display auto-compact output
            JsonNode response = callLlm(text -> {});
            String summary = response.path("choices").path(0).path("message").path("content").asText("");

            // Rebuild messages: summary + recent messages
            messages.clear();
            if (!summary.isEmpty()) {
                messages.add(Map.of("role", "user",
                        "content", "[Auto-compacted conversation summary]\n" + summary));
                messages.add(Map.of("role", "assistant",
                        "content", "Understood. I have the context from our earlier conversation. Continuing."));
            }
            // Add back the recent (non-summarized) messages
            for (int i = splitAt; i < savedMessages.size(); i++) {
                messages.add(savedMessages.get(i));
            }
        } catch (IOException e) {
            // Restore original messages on failure
            messages.clear();
            messages.addAll(savedMessages);
            throw e;
        }
    }

    /**
     * Hard trim: remove oldest messages to fit context window (last resort).
     */
    private void hardTrimMessages(int totalTokens, int availableTokens) {
        int tokensToRemove = totalTokens - availableTokens;
        int removedTokens = 0;
        int removeUpTo = 0;

        for (int i = 0; i < messages.size() && removedTokens < tokensToRemove; i++) {
            removedTokens += estimateTokens(messages.get(i));
            removeUpTo = i + 1;
        }

        // Don't break tool_call / tool_result pairs
        while (removeUpTo < messages.size()) {
            Map<String, Object> next = messages.get(removeUpTo);
            if ("tool".equals(next.get("role"))) {
                removeUpTo++;
            } else if ("assistant".equals(next.get("role")) && next.containsKey("tool_calls")) {
                removeUpTo++;
            } else {
                break;
            }
        }

        if (removeUpTo > 0 && removeUpTo < messages.size()) {
            messages.subList(0, removeUpTo).clear();
            messages.addFirst(Map.of(
                    "role", "user",
                    "content", "[Earlier conversation history was trimmed to fit context window. " +
                            "Continue based on the remaining messages below.]"
            ));
        }
    }

    /**
     * Call the LLM API with the current message history.
     * Streams text content to onText callback, returns the full response JSON.
     */
    private JsonNode callLlm(TextCallback onText) throws IOException {
        // Trim history if approaching context window limit
        trimMessages();

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
        sysMsg.put("content", systemPrompt);

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

        String requestJson = MAPPER.writeValueAsString(requestBody);

        // Track input tokens (estimate from request payload)
        totalInputTokens += Math.max(1, requestJson.length() / 4);

        RequestBody body = RequestBody.create(
                requestJson,
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

        // Track token usage (estimate output tokens from content + tool calls)
        int outputChars = fullContent.length();
        for (ToolCallAccumulator acc : toolCallMap.values()) {
            outputChars += (acc.name != null ? acc.name.length() : 0) + acc.arguments.length();
        }
        totalOutputTokens += Math.max(1, outputChars / 4);

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

    /**
     * Compact conversation history by summarizing it via the LLM.
     * Replaces all messages with a single summary message.
     */
    public String compactHistory(TextCallback onText) throws IOException {
        if (messages.isEmpty()) {
            return "Nothing to compact.";
        }

        int preCompactCount = messages.size();

        // Build a summary prompt
        String summaryRequest = "Summarize the conversation so far in a concise way, " +
                "preserving key decisions, file paths mentioned, and important context. " +
                "Be brief but complete. Format as bullet points.";

        // Temporarily add the summary request
        messages.add(Map.of("role", "user", "content", summaryRequest));

        String summary;
        try {
            JsonNode response = callLlm(onText);
            summary = response.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException e) {
            // Remove the temporary summary request on failure
            if (messages.size() > preCompactCount) {
                messages.subList(preCompactCount, messages.size()).clear();
            }
            throw e;
        }

        // Replace all messages with the summary
        messages.clear();
        if (!summary.isEmpty()) {
            messages.add(Map.of("role", "user",
                    "content", "[Compacted conversation summary]\n" + summary));
            messages.add(Map.of("role", "assistant",
                    "content", "I've reviewed the conversation summary. I'm ready to continue. " +
                            "What would you like to work on next?"));
        }

        int estimatedTokens = summary.length() / 4;
        return "Compacted conversation to ~%d tokens.".formatted(estimatedTokens);
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
