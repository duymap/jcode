package com.jcode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web search tool using DuckDuckGo HTML (no API key required).
 */
public class WebSearchTool implements Tool {

    private static final String DDG_URL = "https://html.duckduckgo.com/html/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int DEFAULT_COUNT = 10;
    private static final int MAX_COUNT = 20;

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web using DuckDuckGo. Use this when you need to find documentation, " +
                "API references, library usage examples, or any information you don't already know. " +
                "Returns titles, URLs, and snippets for each result.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("query"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "Search query"));
        props.put("count", Map.of("type", "integer", "description",
                "Number of results to return (default: 10, max: 20)"));
        schema.put("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonNode args, String cwd) throws Exception {
        String query = args.get("query").asText();
        int count = args.has("count") ? Math.min(args.get("count").asInt(DEFAULT_COUNT), MAX_COUNT) : DEFAULT_COUNT;

        String url = DDG_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .get();

        Elements results = doc.select(".result");
        if (results.isEmpty()) {
            return "No results found for: " + query;
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Element result : results) {
            if (shown >= count) break;

            Element titleLink = result.selectFirst(".result__a");
            Element snippet = result.selectFirst(".result__snippet");

            if (titleLink == null) continue;

            String title = titleLink.text();
            String href = extractUrl(titleLink.attr("href"));
            String desc = snippet != null ? snippet.text() : "";

            if (title.isEmpty() || href.isEmpty()) continue;

            shown++;
            sb.append(shown).append(". ").append(title).append('\n');
            sb.append("   ").append(href).append('\n');
            if (!desc.isEmpty()) {
                sb.append("   ").append(desc).append('\n');
            }
            sb.append('\n');
        }

        if (shown == 0) {
            return "No results found for: " + query;
        }

        return sb.toString().stripTrailing();
    }

    /**
     * DuckDuckGo HTML wraps URLs in redirect links like /l/?uddg=ENCODED_URL&...
     * Extract the actual destination URL.
     */
    private String extractUrl(String href) {
        if (href == null || href.isEmpty()) return "";
        if (href.contains("uddg=")) {
            try {
                int start = href.indexOf("uddg=") + 5;
                int end = href.indexOf('&', start);
                String encoded = end > start ? href.substring(start, end) : href.substring(start);
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return href;
            }
        }
        return href;
    }
}
