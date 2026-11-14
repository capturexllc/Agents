package com.vamsdest.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into chunks of at most {@code maxChars}, breaking at whitespace where possible. Consecutive chunks share
 * about {@code overlap} characters, so a sentence that straddles a boundary is still whole in one chunk.
 */
public final class Chunker {
    private Chunker() {
    }

    public static List<String> split(String text, int maxChars, int overlap) {
        if (maxChars <= overlap) {
            throw new IllegalArgumentException("maxChars must be greater than overlap");
        }
        String clean = text.replaceAll("[\\t\\x0B\\f\\r ]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
        List<String> chunks = new ArrayList<>();
        if (clean.isEmpty()) {
            return chunks;
        }
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(clean.length(), start + maxChars);
            if (end < clean.length()) {
                int space = clean.lastIndexOf(' ', end);
                if (space > start + maxChars / 2) {
                    end = space;
                }
            }
            String chunk = clean.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= clean.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}
