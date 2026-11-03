package com.vamsdest.agent;

import java.time.Instant;
import java.util.List;

public record TripEvent(
        String type,
        String tripId,
        String agentId,
        String state,
        String message,
        List<SourceReference> sources,
        Instant timestamp) {
    public static TripEvent status(String tripId, String agentId, String state, String message) {
        return new TripEvent("agent_status", tripId, agentId, state, message, List.of(), Instant.now());
    }

    public static TripEvent result(String tripId, String agentId, String state, String message,
                                   List<SourceReference> sources) {
        return new TripEvent("agent_result", tripId, agentId, state, message, sources, Instant.now());
    }

    public static TripEvent summary(String tripId, String message, List<SourceReference> sources) {
        return new TripEvent("trip_summary", tripId, null, "completed", message, sources, Instant.now());
    }

    public static TripEvent complete(String tripId, String state, String message) {
        return new TripEvent("trip_complete", tripId, null, state, message, List.of(), Instant.now());
    }
}