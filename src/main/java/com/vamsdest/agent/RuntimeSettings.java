package com.vamsdest.agent;

import java.util.Map;

/**
 * Settings that change how agents run. Defaults come from environment variables; the {@code runtime.yml} document
 * in the prompt store (S3, with a bundled fallback) overrides any value it sets.
 */
public record RuntimeSettings(
        String modelId,
        int maxTokens,
        int maxTurns,
        int specialistSearches,
        int chatSearches,
        int failureRateThreshold,
        int slidingWindowSize,
        int minimumCalls,
        int openSeconds,
        int policySearches) {

    /** Settings without a policy-search budget (the default of 3 applies). */
    public RuntimeSettings(String modelId, int maxTokens, int maxTurns, int specialistSearches, int chatSearches,
            int failureRateThreshold, int slidingWindowSize, int minimumCalls, int openSeconds) {
        this(modelId, maxTokens, maxTurns, specialistSearches, chatSearches, failureRateThreshold, slidingWindowSize,
                minimumCalls, openSeconds, 3);
    }

    /** Applies overrides from a parsed {@code runtime.yml}. Missing keys keep the current value. */
    public RuntimeSettings overlay(Map<String, Object> config) {
        Map<String, Object> model = section(config, "model");
        Map<String, Object> agent = section(config, "agent");
        Map<String, Object> breakers = section(config, "breakers");
        return new RuntimeSettings(
                text(model, "id", modelId),
                integer(model, "maxTokens", maxTokens),
                integer(agent, "maxTurns", maxTurns),
                integer(agent, "specialistSearches", specialistSearches),
                integer(agent, "chatSearches", chatSearches),
                integer(breakers, "failureRateThreshold", failureRateThreshold),
                integer(breakers, "slidingWindowSize", slidingWindowSize),
                integer(breakers, "minimumCalls", minimumCalls),
                integer(breakers, "openSeconds", openSeconds),
                integer(agent, "policySearches", policySearches));
    }

    private static Map<String, Object> section(Map<String, Object> config, String name) {
        Object value = config == null ? null : config.get(name);
        return value instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static int integer(Map<String, Object> section, String key, int fallback) {
        Object value = section.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text.strip());
        }
        return fallback;
    }

    private static String text(Map<String, Object> section, String key, String fallback) {
        Object value = section.get(key);
        return value instanceof String text && !text.isBlank() ? text.strip() : fallback;
    }
}
