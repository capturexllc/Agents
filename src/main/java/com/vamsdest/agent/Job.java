package com.vamsdest.agent;

/**
 * Background work for a trip run. Sent as JSON through the job queue on AWS, or handed to an in-process executor
 * locally. {@code requestId} and {@code question} are set only for {@link #CHAT} jobs.
 */
public record Job(String type, String tripId, String requestId, String question) {
    public static final String TRIP = "trip";
    public static final String CHAT = "chat";

    public static Job trip(String tripId) {
        return new Job(TRIP, tripId, null, null);
    }

    public static Job chat(String tripId, String requestId, String question) {
        return new Job(CHAT, tripId, requestId, question);
    }
}
