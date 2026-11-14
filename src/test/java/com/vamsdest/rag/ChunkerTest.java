package com.vamsdest.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    @Test
    void shortTextIsOneChunk() {
        assertEquals(List.of("Refunds take 14 days."), Chunker.split("  Refunds take 14 days.  ", 100, 10));
    }

    @Test
    void emptyTextProducesNoChunks() {
        assertTrue(Chunker.split("   \n\n ", 100, 10).isEmpty());
    }

    @Test
    void longTextIsSplitWithinTheLimitAndNeighboursOverlap() {
        String text = "word ".repeat(200).strip();
        List<String> chunks = Chunker.split(text, 100, 20);

        assertTrue(chunks.size() > 2);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 100, "chunk too long: " + chunk.length());
        }
        String lastWordOfFirst = chunks.get(0).substring(chunks.get(0).lastIndexOf(' ') + 1);
        assertTrue(chunks.get(1).startsWith("word") && chunks.get(1).contains(lastWordOfFirst));
    }

    @Test
    void overlapMustBeSmallerThanChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> Chunker.split("text", 10, 10));
    }
}
