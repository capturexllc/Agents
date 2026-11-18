package com.vamsdest.rag;

import java.util.List;

/**
 * Stores the text of document chunks. Vectors live in a {@link VectorIndex}; both use the same chunk key,
 * {@code docId#index}, so a vector match can be turned back into text.
 */
public interface ChunkStore {

    /** A chunk of a document: its text and where it came from. */
    record StoredChunk(String docId, int index, String source, String location, String text) {
    }

    /** The key shared by a chunk's text and its vector. */
    static String key(String docId, int index) {
        return docId + "#" + String.format("%06d", index);
    }

    /** Replaces every chunk of {@code docId} with {@code chunks}. */
    void replaceDocument(String docId, List<StoredChunk> chunks);

    /** Keys of the chunks currently stored for {@code docId}. */
    List<String> keysOf(String docId);

    /** The chunks with these keys, in no particular order. Missing keys are skipped. */
    List<StoredChunk> getAll(List<String> keys);

    /** A store with nothing in it, for environments where no document corpus is configured. */
    ChunkStore EMPTY = new ChunkStore() {
        @Override
        public void replaceDocument(String docId, List<StoredChunk> chunks) {
            throw new UnsupportedOperationException("no document store is configured");
        }

        @Override
        public List<String> keysOf(String docId) {
            return List.of();
        }

        @Override
        public List<StoredChunk> getAll(List<String> keys) {
            return List.of();
        }
    };
}
