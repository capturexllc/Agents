package com.vamsdest.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyRetrieverTest {

    /** An index with fixed matches, so the retriever's own logic is what gets tested. */
    private static VectorIndex index(List<VectorIndex.Match> matches) {
        return new VectorIndex() {
            @Override
            public void put(List<Entry> entries) {
            }

            @Override
            public void delete(List<String> keys) {
            }

            @Override
            public List<Match> query(float[] vector, int topK) {
                return matches;
            }
        };
    }

    /** A chunk store that holds the given chunks and returns them by key. */
    private static ChunkStore store(ChunkStore.StoredChunk... chunks) {
        List<ChunkStore.StoredChunk> all = List.of(chunks);
        return new ChunkStore() {
            @Override
            public void replaceDocument(String docId, List<StoredChunk> stored) {
            }

            @Override
            public List<String> keysOf(String docId) {
                return List.of();
            }

            @Override
            public List<StoredChunk> getAll(List<String> keys) {
                List<StoredChunk> found = new ArrayList<>();
                for (StoredChunk chunk : all) {
                    if (keys.contains(ChunkStore.key(chunk.docId(), chunk.index()))) {
                        found.add(chunk);
                    }
                }
                return found;
            }
        };
    }

    @Test
    void returnsTextForCloseMatchesInOrderAndDropsWeakOnes() {
        PolicyRetriever retriever = new PolicyRetriever(text -> new float[] {1f},
                index(List.of(
                        new VectorIndex.Match("refunds#000000", 0.10),
                        new VectorIndex.Match("baggage#000001", 0.90),
                        new VectorIndex.Match("refunds#000001", 0.30))),
                store(
                        new ChunkStore.StoredChunk("refunds", 0, "refunds.pdf", "page 1", "Full refund in 48 hours."),
                        new ChunkStore.StoredChunk("baggage", 1, "baggage.pdf", "page 2", "Two bags allowed."),
                        new ChunkStore.StoredChunk("refunds", 1, "refunds.pdf", "page 2", "Fees apply after that.")));

        List<PolicyRetriever.Hit> hits = retriever.search("refund rules");

        assertEquals(2, hits.size());
        assertEquals("Full refund in 48 hours.", hits.get(0).text());
        assertEquals("refunds.pdf", hits.get(0).source());
        assertEquals("page 2", hits.get(1).location());
        assertTrue(hits.get(0).score() > hits.get(1).score());
    }

    @Test
    void blankQueryReturnsNothing() {
        PolicyRetriever retriever = new PolicyRetriever(text -> new float[] {1f},
                index(List.of(new VectorIndex.Match("a#000000", 0.0))), ChunkStore.EMPTY);

        assertTrue(retriever.search("   ").isEmpty());
    }

    @Test
    void matchWithoutStoredTextIsSkipped() {
        PolicyRetriever retriever = new PolicyRetriever(text -> new float[] {1f},
                index(List.of(new VectorIndex.Match("gone#000000", 0.05))), ChunkStore.EMPTY);

        assertTrue(retriever.search("anything").isEmpty());
    }
}
