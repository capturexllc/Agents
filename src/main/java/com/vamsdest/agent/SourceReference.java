package com.vamsdest.agent;

import java.time.Instant;

public record SourceReference(String title, String url, String excerpt, Instant retrievedAt) {
}
