package com.vamsdest.agent;

import com.vamsdest.api.TripRequest;
import java.time.Instant;

public record RunRecord(String id, String ownerId, TripRequest request, Instant createdAt, String status) {
    public static final String PLANNING = "planning";
    public static final String COMPLETED = "completed";
    public static final String PARTIAL = "partial";

    public RunRecord withStatus(String newStatus) {
        return new RunRecord(id, ownerId, request, createdAt, newStatus);
    }
}
