package com.vamsdest.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

/** Embeds text with an Amazon Titan text embeddings model on Bedrock (default amazon.titan-embed-text-v2:0). */
public final class BedrockEmbedder implements Embedder {
    static final int MAX_INPUT_CHARS = 8000;
    /** Embedding quotas are per account and per second; ingest backs off instead of failing the whole document. */
    static final int MAX_ATTEMPTS = 8;
    static final long BASE_BACKOFF_MILLIS = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BedrockRuntimeClient client;
    private final String modelId;

    public BedrockEmbedder(BedrockRuntimeClient client, String modelId) {
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public float[] embed(String text) {
        String input = text.length() <= MAX_INPUT_CHARS ? text : text.substring(0, MAX_INPUT_CHARS);
        try {
            String body = JSON.writeValueAsString(Map.of("inputText", input));
            String response = invokeWithBackoff(body);
            JsonNode vector = JSON.readTree(response).path("embedding");
            if (!vector.isArray() || vector.isEmpty()) {
                throw new IllegalStateException("Embedding model returned no vector");
            }
            float[] result = new float[vector.size()];
            for (int index = 0; index < result.length; index++) {
                result[index] = (float) vector.get(index).asDouble();
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException("Embedding request failed", exception);
        }
    }

    private String invokeWithBackoff(String body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.invokeModel(InvokeModelRequest.builder()
                        .modelId(modelId)
                        .contentType("application/json")
                        .accept("application/json")
                        .body(SdkBytes.fromUtf8String(body))
                        .build()).body().asUtf8String();
            } catch (RuntimeException failure) {
                if (!isThrottle(failure) || attempt >= MAX_ATTEMPTS) {
                    throw failure;
                }
                sleep(BASE_BACKOFF_MILLIS * (1L << (attempt - 1)));
            }
        }
    }

    /** The SDK reports quota errors as ThrottlingException or as a client error with HTTP 429. */
    static boolean isThrottle(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ThrottlingException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Status Code: 429") || message.contains("Too many requests"))) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry embedding", interrupted);
        }
    }
}
