package com.vamsdest.agent;

import java.util.List;

public interface TravelResearchProvider {
    List<SourceReference> search(String query);

    boolean isDemo();
}