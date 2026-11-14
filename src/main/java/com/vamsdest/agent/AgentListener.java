package com.vamsdest.agent;

/** Receives progress callbacks while an agent works. Used to write run events for pollers. */
public interface AgentListener {
    AgentListener NONE = new AgentListener() {
    };

    default void onSearch(String query) {
    }

    default void onThinking() {
    }
}
