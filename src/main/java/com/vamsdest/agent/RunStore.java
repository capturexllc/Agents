package com.vamsdest.agent;

import com.vamsdest.api.TripRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable-or-local storage for trip runs and their ordered events. Local development uses
 * {@link InMemoryRunStore}; the AWS deployment uses {@link DynamoRunStore} so any Lambda instance can read
 * events written by any other.
 */
public interface RunStore {
    RunRecord create(String runId, String ownerId, TripRequest request, Instant createdAt);

    /** Returns the run only when it belongs to {@code ownerId}; other owners see nothing. */
    Optional<RunRecord> findOwned(String runId, String ownerId);

    /** Unscoped lookup for background workers, which act on a run they were given by the API. */
    Optional<RunRecord> find(String runId);

    /** Appends an event and returns it with its assigned sequence number. */
    TripEvent append(String runId, TripEvent event);

    /** Events with {@code seq > afterSeq}, in sequence order. */
    List<TripEvent> eventsAfter(String runId, long afterSeq);

    void setStatus(String runId, String status);
}
