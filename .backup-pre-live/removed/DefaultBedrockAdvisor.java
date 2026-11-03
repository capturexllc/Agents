package com.vamsdest.agent;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DefaultBedrockAdvisor implements BedrockAdvisor {
    private final BedrockClientAdvisor bedrockClientAdvisor;

    public DefaultBedrockAdvisor(BedrockClientAdvisor bedrockClientAdvisor) {
        this.bedrockClientAdvisor = bedrockClientAdvisor;
    }

    @Override
    public String summarize(String agentName, String request, List<SourceReference> sources) {
        if (sources.isEmpty()) {
            return "No research sources were returned. Check the configured travel search provider and try again.";
        }
        if (sources.stream().allMatch(SourceReference::demo)) {
            return "Demo mode only: no live travel research or availability was checked. "
                    + "Configure Tavily and AWS Bedrock to receive sourced recommendations.";
        }
        if (bedrockClientAdvisor.isEnabled()) {
            return bedrockClientAdvisor.summarize(agentName, request, sources);
        }
        return sources.stream()
                .map(source -> "- " + source.title() + ": " + source.excerpt())
                .collect(Collectors.joining("\n", "Live search sources (unsummarized):\n", ""));
    }
}