package com.vamsdest.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.junit.jupiter.api.Test;

class LocalEmbedderTest {

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @Test
    void producesMiniLmVectorsThatRankRelatedTextHigher() {
        LocalEmbedder embedder = new LocalEmbedder(() -> new AllMiniLmL6V2EmbeddingModel());
        float[] refund = embedder.embed("Refunds for cancelled flights are paid back to the original card.");
        float[] related = embedder.embed("If you cancel a plane ticket, you get your money back.");
        float[] unrelated = embedder.embed("The museum opens at nine and sells postcards.");

        assertEquals(384, refund.length);
        assertTrue(cosine(refund, related) > cosine(refund, unrelated));
    }
}
