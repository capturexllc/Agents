package com.vamsdest.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vamsdest.search.mode", havingValue = "tavily")
public class TavilyResearchProvider implements TravelResearchProvider {
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public TavilyResearchProvider(
            @Value("${vamsdest.search.api-key:}") String apiKey,
            ObjectMapper objectMapper) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("TAVILY_API_KEY is required when SEARCH_MODE=tavily");
        }
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SourceReference> search(String query) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "search_depth", "basic",
                    "max_results", 5,
                    "include_answer", false));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.tavily.com/search"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Travel search provider returned HTTP " + response.statusCode());
            }
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            List<SourceReference> sources = new ArrayList<>();
            for (JsonNode result : results) {
                sources.add(new SourceReference(
                        result.path("title").asText("Untitled source"),
                        result.path("url").asText(""),
                        truncate(result.path("content").asText(""), 900),
                        false));
            }
            return List.copyOf(sources);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Travel search was interrupted", exception);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Travel search is temporarily unavailable", exception);
        }
    }

    @Override
    public boolean isDemo() {
        return false;
    }

    private static String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength) + "...";
    }
}