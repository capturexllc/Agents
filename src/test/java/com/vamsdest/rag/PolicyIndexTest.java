package com.vamsdest.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolicyIndexTest {

    private static ChunkStore.StoredChunk chunk(String source, float[] vector) {
        return new ChunkStore.StoredChunk("doc", 0, source, "page 1", source + " text", vector);
    }

    @Test
    void ranksByCosineSimilarityAndDropsWeakMatches() {
        PolicyIndex index = new PolicyIndex(() -> List.of(
                chunk("refunds", new float[] {1f, 0f}),
                chunk("baggage", new float[] {0f, 1f}),
                chunk("mixed", new float[] {1f, 1f})),
                Duration.ofMinutes(5), Clock.systemUTC());

        List<PolicyIndex.Hit> hits = index.search(new float[] {1f, 0.1f}, 3, 0.5);

        assertEquals("refunds", hits.get(0).source());
        assertTrue(hits.stream().noneMatch(hit -> hit.source().equals("baggage")), "orthogonal chunk is dropped");
        assertTrue(hits.get(0).score() > hits.get(1).score());
    }

    @Test
    void reloadsTheCorpusAfterTheTtl() {
        AtomicInteger loads = new AtomicInteger();
        Instant[] now = {Instant.parse("2030-01-01T00:00:00Z")};
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        PolicyIndex index = new PolicyIndex(() -> {
            loads.incrementAndGet();
            return List.of(chunk("a", new float[] {1f}));
        }, Duration.ofMinutes(5), clock);

        index.size();
        index.size();
        assertEquals(1, loads.get());

        now[0] = now[0].plus(Duration.ofMinutes(6));
        index.size();
        assertEquals(2, loads.get());
    }

    @Test
    void retrieverEmbedsTheQueryBeforeSearching() {
        Embedder embedder = text -> new float[] {1f, 0f};
        PolicyRetriever retriever = new PolicyRetriever(embedder, new PolicyIndex(
                () -> List.of(chunk("refunds", new float[] {1f, 0f})), Duration.ofMinutes(5), Clock.systemUTC()));

        assertEquals("refunds", retriever.search("refund rules").get(0).source());
        assertTrue(retriever.search("   ").isEmpty());
    }
}
