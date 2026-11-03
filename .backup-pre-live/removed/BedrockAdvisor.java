package com.vamsdest.agent;

import java.util.List;

public interface BedrockAdvisor {
    String summarize(String agentName, String request, List<SourceReference> sources);
}