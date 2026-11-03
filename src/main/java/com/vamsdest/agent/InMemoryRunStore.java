package com.vamsdest.agent;

import com.vamsdest.api.TripRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** Process-local store for the Spring development server. Runs are lost on restart. */
@Component
public class InMemoryRunStore implements RunStore {
    private static final Duration RETENTION = Duration.ofHours(1);
    private final ConcurrentMap<String, Entry> runs = new ConcurrentHashMap<>();

    @Override
    public RunRecord create(String runId, String ownerId, TripRequest request, Instant createdAt) {
        Instant cutoff = Instant.now().minus(RETENTION);
        runs.values().removeIf(entry -> entry.record.createdAt().isBefore(cutoff));
        RunRecord record = new RunRecord(runId, ownerId, request, createdAt, RunRecord.PLANNING);
        runs.put(runId, new Entry(record));
        return record;
    }

    @Override
    public Optional<RunRecord> findOwned(String runId, String ownerId) {
        return find(runId).filter(record -> record.ownerId().equals(ownerId));
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        Entry entry = runs.get(runId);
        return entry == null ? Optional.empty() : Optional.of(entry.record);
    }

    @Override
    public TripEvent append(String runId, TripEvent event) {
        Entry entry = runs.get(runId);
        if (entry == null) {
            throw new IllegalStateException("Unknown trip run " + runId);
        }
        synchronized (entry.events) {
            TripEvent stored = event.withSeq(entry.events.size() + 1L);
            entry.events.add(stored);
            return stored;
        }
    }

    @Override
    public List<TripEvent> eventsAfter(String runId, long afterSeq) {
        Entry entry = runs.get(runId);
        if (entry == null) {
            return List.of();
        }
        synchronized (entry.events) {
            int from = (int) Math.min(Math.max(afterSeq, 0), entry.events.size());
            return new ArrayList<>(entry.events.subList(from, entry.events.size()));
        }
    }

    @Override
    public void setStatus(String runId, String status) {
        runs.computeIfPresent(runId, (id, entry) -> {
            entry.record = entry.record.withStatus(status);
            return entry;
        });
    }

    private static final class Entry {
        private volatile RunRecord record;
        private final List<TripEvent> events = new ArrayList<>();

        private Entry(RunRecord record) {
            this.record = record;
        }
    }
}
