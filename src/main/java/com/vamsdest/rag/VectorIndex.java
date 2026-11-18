package com.vamsdest.rag;

import java.util.List;

/** A similarity index over chunk vectors. Keys are chunk keys (see {@link ChunkStore#key}). */
public interface VectorIndex {

    /** A vector to store under {@code key}. */
    record Entry(String key, float[] vector) {
    }

    /** A stored vector that matched a query. A smaller distance is a closer match (cosine distance). */
    record Match(String key, double distance) {
    }

    void put(List<Entry> entries);

    void delete(List<String> keys);

    /** The {@code topK} closest vectors, nearest first. */
    List<Match> query(float[] vector, int topK);

    /** An index with nothing in it, for environments where no document corpus is configured. */
    VectorIndex EMPTY = new VectorIndex() {
        @Override
        public void put(List<Entry> entries) {
            throw new UnsupportedOperationException("no vector index is configured");
        }

        @Override
        public void delete(List<String> keys) {
        }

        @Override
        public List<Match> query(float[] vector, int topK) {
            return List.of();
        }
    };
}
