package com.jcode.extensions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcode.model.ReasoningModelConfig;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Planning extension: automatically generates an execution plan for non-trivial tasks
 * before the main coding model processes them.
 */
public class PlanningExtension {

    private static final String PLANNING_PROMPT = """
            You are a senior software engineer. Given a coding task, create a clear, concise execution plan.

            Output a brief summary followed by 3-5 numbered steps.

            Format:
            **Plan:** [One sentence describing the approach]
            1. [Step 1 - be specific and actionable]
            2. [Step 2]
            3. [Step 3]

            Be concise and focus on WHAT to do, not WHY. No explanations, no thinking process, no extra text.""";

    static final String PLAN_MARKER = "[[jcode_plan]]";

    private static final Set<String> GREETINGS = Set.of(
            "hi", "hello", "hey", "thanks", "thank you", "bye", "goodbye"
    );

    private static final List<String> QUESTION_STARTERS = List.of(
            "what is", "what's", "who is", "who's", "where is", "where's",
            "when is", "when's", "why is", "why's", "how does", "how is",
            "is there", "are there", "can you tell", "could you tell",
            "do you know", "does this"
    );

    private static final List<String> INFO_PATTERNS = List.of(
            "explain", "describe", "show me", "list", "what are", "tell me about"
    );

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReasoningModelConfig reasoningModel;

    public PlanningExtension(ReasoningModelConfig reasoningModel) {
        this.reasoningModel = reasoningModel;
    }

    /**
     * Check if planning should be skipped for this input.
     */
    public static boolean shouldSkipPlanning(String text) {
        String trimmed = text.trim().toLowerCase();

        if (trimmed.isEmpty() || trimmed.length() < 5) return true;
        if (trimmed.startsWith("/")) return true;
        if (text.contains(PLAN_MARKER)) return true;

        String[] words = trimmed.split("\\s+");
        if (words.length < 3) return true;

        // Check greetings
        for (String g : GREETINGS) {
            if (trimmed.equals(g) || trimmed.startsWith(g + " ") || trimmed.startsWith(g + ",")) {
                return true;
            }
        }

        // Check simple questions
        for (String q : QUESTION_STARTERS) {
            if (trimmed.startsWith(q)) return true;
        }

        // Check info requests with short input
        if (words.length < 8) {
            for (String p : INFO_PATTERNS) {
                if (trimmed.startsWith(p)) return true;
            }
        }

        return false;
    }

    /**
     * Generate an execution plan for the given task.
     *
     * @return the plan text, or null if planning fails or is skipped
     */
    public String generatePlan(String taskText) {
        if (shouldSkipPlanning(taskText)) return null;

        try {
            String planText;
            if (reasoningModel != null) {
                planText = callReasoningModel(taskText);
            } else {
                // No reasoning model - skip planning
                return null;
            }

            if (planText == null || planText.isBlank()) return null;
            return planText.trim();
        } catch (Exception e) {
            System.err.println("  Planning error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Format plan text for display.
     */
    public static String formatPlanDisplay(String planText) {
        if (planText == null || planText.isBlank()) return "No plan generated";

        String[] lines = planText.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .toArray(String[]::new);

        if (lines.length == 0) return "No plan generated";

        StringBuilder sb = new StringBuilder("\n\u001b[36m\u001b[1m");
        sb.append("  Plan:\u001b[0m\n");
        for (String line : lines) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Augment user input with plan context.
     */
    public static String augmentWithPlan(String originalText, String planText) {
        return PLAN_MARKER + "\n\nPLAN:\n" + planText + "\n\nTASK:\n" + originalText;
    }

    private String callReasoningModel(String taskText) throws IOException {
        Map<String, Object> requestBody = Map.of(
                "model", reasoningModel.modelId(),
                "messages", List.of(
                        Map.of("role", "system", "content", PLANNING_PROMPT),
                        Map.of("role", "user", "content", "Task: " + taskText)
                ),
                "temperature", 0.3,
                "max_tokens", 1000,
                "stream", false
        );

        RequestBody body = RequestBody.create(
                MAPPER.writeValueAsString(requestBody),
                MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
                .url(reasoningModel.baseUrl() + "/chat/completions")
                .post(body)
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }

            JsonNode root = MAPPER.readTree(response.body().string());
            String content = root.path("choices").path(0).path("message").path("content").asText("");

            // Strip <think>...</think> tags
            content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
            return content;
        }
    }
}
