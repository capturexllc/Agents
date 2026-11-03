package com.vamsdest.agent;

import java.time.Instant;
import java.util.List;

/**
 * One progress or result event for a trip run. {@code seq} is assigned by the {@link RunStore} when the event
 * is appended and orders events for polling clients. {@code correlationId} ties a chat answer to its request.
 */
public record TripEvent(
        long seq,
        String type,
        String tripId,
        String agentId,
        String correlationId,
        String state,
        String message,
        List<SourceReference> sources,
        Instant timestamp) {

    public TripEvent withSeq(long seq) {
        return new TripEvent(seq, type, tripId, agentId, correlationId, state, message, sources, timestamp);
    }

    public static TripEvent status(String tripId, String agentId, String state, String message) {
        return new TripEvent(0, "agent_status", tripId, agentId, null, state, message, List.of(), Instant.now());
    }

    public static TripEvent tool(String tripId, String agentId, String query) {
        return new TripEvent(0, "agent_tool", tripId, agentId, null, "running", query, List.of(), Instant.now());
    }

    public static TripEvent result(String tripId, String agentId, String state, String message,
                                   List<SourceReference> sources) {
        return new TripEvent(0, "agent_result", tripId, agentId, null, state, message, sources, Instant.now());
    }

    public static TripEvent summary(String tripId, String message, List<SourceReference> sources) {
        return new TripEvent(0, "trip_summary", tripId, null, null, "completed", message, sources, Instant.now());
    }

    public static TripEvent complete(String tripId, String state, String message) {
        return new TripEvent(0, "trip_complete", tripId, null, null, state, message, List.of(), Instant.now());
    }

    public static TripEvent chatAnswer(String tripId, String correlationId, String state, String message,
                                       List<SourceReference> sources) {
        return new TripEvent(0, "chat_answer", tripId, null, correlationId, state, message, sources, Instant.now());
    }
}
