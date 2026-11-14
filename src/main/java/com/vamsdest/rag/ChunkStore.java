package com.vamsdest.rag;

import java.util.List;

/** Stores document chunks and their vectors. */
public interface ChunkStore {

    /** A chunk of a document, with the vector of its text. */
    record StoredChunk(String docId, int index, String source, String location, String text, float[] vector) {
    }

    /** Replaces every chunk of {@code docId} with {@code chunks}. */
    void replaceDocument(String docId, List<StoredChunk> chunks);

    /** Every stored chunk. The corpus is small, so the index loads it whole and caches it. */
    List<StoredChunk> loadAll();

    /** A store with nothing in it, for environments where no document corpus is configured. */
    ChunkStore EMPTY = new ChunkStore() {
        @Override
        public void replaceDocument(String docId, List<StoredChunk> chunks) {
            throw new UnsupportedOperationException("no document store is configured");
        }

        @Override
        public List<StoredChunk> loadAll() {
            return List.of();
        }
    };
}
