package com.vamsdest.rag;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * In-memory vector index over the policy corpus. The chunks are loaded from the store on first use and reloaded after
 * {@code ttl}, so an upload to S3 becomes searchable within that window. Search is exact cosine similarity, which is
 * fine for a corpus of a few thousand chunks.
 */
public final class PolicyIndex {

    /** A chunk that matched a query, with its similarity score (1.0 is identical direction). */
    public record Hit(String source, String location, String text, double score) {
    }

    private record Entry(ChunkStore.StoredChunk chunk, double norm) {
    }

    private final Supplier<List<ChunkStore.StoredChunk>> loader;
    private final Duration ttl;
    private final Clock clock;
    private List<Entry> entries;
    private Instant loadedAt = Instant.EPOCH;

    public PolicyIndex(Supplier<List<ChunkStore.StoredChunk>> loader, Duration ttl, Clock clock) {
        this.loader = loader;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Returns up to {@code limit} chunks scoring at least {@code minScore}, best first. */
    public synchronized List<Hit> search(float[] query, int limit, double minScore) {
        List<Entry> current = current();
        double queryNorm = norm(query);
        if (queryNorm == 0) {
            return List.of();
        }
        return current.stream()
                .filter(entry -> entry.chunk().vector().length == query.length)
                .map(entry -> new Hit(entry.chunk().source(), entry.chunk().location(), entry.chunk().text(),
                        dot(query, entry.chunk().vector()) / (queryNorm * entry.norm())))
                .filter(hit -> hit.score() >= minScore)
                .sorted(Comparator.comparingDouble(Hit::score).reversed())
                .limit(limit)
                .toList();
    }

    /** Number of chunks currently indexed (loads the index when needed). */
    public synchronized int size() {
        return current().size();
    }

    private List<Entry> current() {
        Instant now = clock.instant();
        if (entries == null || loadedAt.plus(ttl).isBefore(now)) {
            entries = loader.get().stream()
                    .map(chunk -> new Entry(chunk, norm(chunk.vector())))
                    .toList();
            loadedAt = now;
        }
        return entries;
    }

    static double dot(float[] a, float[] b) {
        return IntStream.range(0, a.length).mapToDouble(index -> (double) a[index] * b[index]).sum();
    }

    static double norm(float[] vector) {
        return Math.sqrt(dot(vector, vector));
    }
}
