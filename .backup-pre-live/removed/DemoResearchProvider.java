package com.vamsdest.agent;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vamsdest.search.mode", havingValue = "demo", matchIfMissing = true)
public class DemoResearchProvider implements TravelResearchProvider {
    @Override
    public List<SourceReference> search(String query) {
        return List.of(new SourceReference(
                "Local demo fixture",
                "",
                "Demo mode is active. No live travel search was performed; configure SEARCH_MODE=tavily and TAVILY_API_KEY for sourced web results.",
                true));
    }

    @Override
    public boolean isDemo() {
        return true;
    }
}