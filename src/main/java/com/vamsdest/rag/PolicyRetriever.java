package com.vamsdest.rag;

import java.util.List;

/** Answers a question from the policy corpus: embeds the query and returns the best-matching chunks. */
public final class PolicyRetriever {
    static final int TOP_K = 4;
    static final double MIN_SCORE = 0.25;

    private final Embedder embedder;
    private final PolicyIndex index;

    public PolicyRetriever(Embedder embedder, PolicyIndex index) {
        this.embedder = embedder;
        this.index = index;
    }

    public List<PolicyIndex.Hit> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return index.search(embedder.embed(query), TOP_K, MIN_SCORE);
    }
}
