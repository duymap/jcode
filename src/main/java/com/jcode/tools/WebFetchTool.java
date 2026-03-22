package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches a web page and extracts readable text content.
 */
public class WebFetchTool implements Tool {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int DEFAULT_MAX_LENGTH = 20_000;
    private static final int MAX_ALLOWED_LENGTH = 100_000;

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public String description() {
        return "Fetch a web page and extract its readable text content. " +
                "Use this to read documentation pages, API references, blog posts, " +
                "or any web page found via web_search. Returns the page title and text content.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("url"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", Map.of("type", "string", "description", "URL to fetch"));
        props.put("maxLength", Map.of("type", "integer", "description",
                "Max characters to return (default: 20000)"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String url = args.get("url").asText();
        int maxLength = args.has("maxLength")
                ? Math.min(args.get("maxLength").asInt(DEFAULT_MAX_LENGTH), MAX_ALLOWED_LENGTH)
                : DEFAULT_MAX_LENGTH;

        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .maxBodySize(2_000_000) // 2MB max
                .followRedirects(true)
                .get();

        // Remove non-content elements
        doc.select("script, style, nav, footer, header, aside, iframe, noscript, " +
                ".sidebar, .menu, .nav, .ad, .advertisement, .cookie-banner, " +
                "#cookie-banner, .modal, .popup").remove();

        String title = doc.title();
        String text = doc.body() != null ? doc.body().text() : "";

        if (text.isEmpty()) {
            return "No readable content found at: " + url;
        }

        // Truncate if needed
        boolean truncated = false;
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
            truncated = true;
        }

        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) {
            sb.append("Title: ").append(title).append("\n\n");
        }
        sb.append(text);
        if (truncated) {
            sb.append("\n\n[Content truncated at ").append(maxLength).append(" characters]");
        }

        return sb.toString();
    }
}
