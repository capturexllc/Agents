package com.vamsdest.rag;

/** Turns text into a vector for similarity search. */
public interface Embedder {
    float[] embed(String text);
}
