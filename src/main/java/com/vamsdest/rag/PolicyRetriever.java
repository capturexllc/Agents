package com.vamsdest.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Answers a question from the policy corpus. The query is embedded, the vector index returns the closest chunk keys,
 * and the chunk store supplies their text. Matches below the minimum similarity are dropped.
 */
public final class PolicyRetriever {
    static final int TOP_K = 4;
    static final double MIN_SCORE = 0.25;

    /** A chunk that matched a query. {@code score} is cosine similarity (1.0 is identical direction). */
    public record Hit(String source, String location, String text, double score) {
    }

    private final Embedder embedder;
    private final VectorIndex vectors;
    private final ChunkStore chunks;

    public PolicyRetriever(Embedder embedder, VectorIndex vectors, ChunkStore chunks) {
        this.embedder = embedder;
        this.vectors = vectors;
        this.chunks = chunks;
    }

    public List<Hit> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<VectorIndex.Match> matches = vectors.query(embedder.embed(query), TOP_K).stream()
                .filter(match -> 1.0 - match.distance() >= MIN_SCORE)
                .toList();
        if (matches.isEmpty()) {
            return List.of();
        }
        Map<String, ChunkStore.StoredChunk> byKey = chunks.getAll(matches.stream()
                        .map(VectorIndex.Match::key)
                        .toList())
                .stream()
                .collect(Collectors.toMap(chunk -> ChunkStore.key(chunk.docId(), chunk.index()),
                        Function.identity(), (first, second) -> first));
        List<Hit> hits = new ArrayList<>();
        for (VectorIndex.Match match : matches) {
            ChunkStore.StoredChunk chunk = byKey.get(match.key());
            if (chunk != null) {
                hits.add(new Hit(chunk.source(), chunk.location(), chunk.text(), 1.0 - match.distance()));
            }
        }
        return hits;
    }
}
