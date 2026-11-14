package com.vamsdest.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptCatalogTest {
    private final PromptCatalog bundled = new PromptCatalog(null, "", "", Duration.ofMinutes(5));

    @Test
    void rendersPlaceholdersAndRejectsMissingValues() {
        assertEquals("Hello Kyoto, May.", PromptCatalog.render("Hello {{place}}, {{when}}.",
                Map.of("place", "Kyoto", "when", "May")));
        assertThrows(IllegalArgumentException.class,
                () -> PromptCatalog.render("Hello {{place}}.", Map.of()));
    }

    @Test
    void replacementTextIsNotInterpretedAsRegex() {
        String value = "$5 " + (char) 92;
        assertEquals("cost " + value + " ok", PromptCatalog.render("cost {{v}} ok", Map.of("v", value)));
    }

    @Test
    void bundledPromptsAreAvailableWithoutS3() {
        assertTrue(bundled.template("role-destination").contains("Destination Agent"));
        assertTrue(bundled.template("task-transport").contains("{{route}}"));
    }

    @Test
    void bundledRuntimeConfigOverlaysDefaults() {
        RuntimeSettings defaults = new RuntimeSettings("model-a", 500, 8, 4, 2, 50, 10, 5, 30);
        RuntimeSettings merged = defaults.overlay(bundled.runtimeConfig());

        assertEquals(1500, merged.maxTokens());
        assertEquals(8, merged.maxTurns());
        assertEquals("model-a", merged.modelId(), "keys the file does not set keep their default");
    }

    @Test
    void overlayChangesOnlyKeysThatAreSet() {
        RuntimeSettings defaults = new RuntimeSettings("model-a", 500, 8, 4, 2, 50, 10, 5, 30);
        RuntimeSettings merged = defaults.overlay(Map.of(
                "model", Map.of("id", "model-b"),
                "agent", Map.of("maxTurns", 3)));

        assertEquals("model-b", merged.modelId());
        assertEquals(3, merged.maxTurns());
        assertEquals(4, merged.specialistSearches());
        assertFalse(merged.equals(defaults));
    }
}
