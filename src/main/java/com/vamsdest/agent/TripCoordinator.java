package com.vamsdest.agent;

import com.vamsdest.api.TripRequest;
import com.vamsdest.rag.PolicyRetriever;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Entry point for planning and chat. Each run reads its settings and prompts (S3, cached), builds the chat model and
 * ReAct agent for those settings, and runs the LangGraph4j trip workflow or a single chat answer. Progress is written
 * to the {@link RunStore} as it happens, so pollers see the work whichever process runs it.
 */
@Service
public class TripCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TripCoordinator.class);

    private final ModelFactory models;
    private final TravelResearchProvider research;
    private final PolicyRetriever policies;
    private final RunStore store;
    private final PromptCatalog prompts;
    private final RuntimeSettings defaults;
    private final CircuitBreakers breakers;
    private final TripGraph tripGraph;

    public TripCoordinator(
            ModelFactory models,
            TravelResearchProvider research,
            PolicyRetriever policies,
            RunStore store,
            @Qualifier("agentExecutor") Executor agentExecutor,
            PromptCatalog prompts,
            RuntimeSettings defaults) {
        this.models = models;
        this.research = research;
        this.policies = policies;
        this.store = store;
        this.prompts = prompts;
        this.defaults = defaults;
        this.breakers = new CircuitBreakers(defaults);
        this.tripGraph = new TripGraph(agentExecutor);
    }

    /** Runs the trip workflow and records the final status. Blocks until done. */
    public void runTrip(String runId, TripRequest request) {
        RuntimeSettings settings = settings();
        ReactAgent agent = agent(settings);
        TripGraph.Result result = tripGraph.run(
                new TripGraph.RunContext(runId, store, request, settings, agent, prompts));

        store.append(runId, TripEvent.summary(runId, result.summary(), result.sources()));
        String state = result.failed() ? RunRecord.PARTIAL : RunRecord.COMPLETED;
        store.setStatus(runId, state);
        store.append(runId, TripEvent.complete(runId, state,
                result.failed() ? "Research finished with one or more agent errors." : "All travel agents finished."));
    }

    /** Answers one follow-up question using the findings already recorded for this trip. */
    public String answer(String runId, TripRequest request, String message) {
        RuntimeSettings settings = settings();
        ReactAgent agent = agent(settings);
        List<TripEvent> events = store.eventsAfter(runId, 0);
        String knownSources = resultSources(events).stream()
                .map(source -> "- " + source.title() + " (" + source.url() + ")")
                .collect(Collectors.joining("\n"));
        String task = prompts.prompt("task-chat", Map.of(
                "destination", request.destination(),
                "dateRange", TripGraph.dateRange(request),
                "budget", TripGraph.budgetHint(request),
                "briefings", String.join("\n\n", eventBriefings(events)),
                "knownSources", knownSources,
                "question", message));
        return agent.run(
                ReactAgent.systemPrompt(prompts, "role-chat", settings.chatSearches()),
                task, settings.chatSearches(), AgentListener.NONE, settings).answer();
    }

    /** The current runtime settings: environment defaults overlaid with runtime.yml from the prompt store. */
    RuntimeSettings settings() {
        try {
            return defaults.overlay(prompts.runtimeConfig());
        } catch (RuntimeException unreadable) {
            log.warn("runtime.yml could not be applied, using defaults: {}", unreadable.getMessage());
            return defaults;
        }
    }

    private ReactAgent agent(RuntimeSettings settings) {
        return new ReactAgent(models.create(settings.modelId()), research, policies, breakers,
                prompts.template("tool-web-search").strip(), prompts.template("tool-policy-search").strip());
    }

    private static List<String> eventBriefings(List<TripEvent> events) {
        return events.stream()
                .filter(event -> "agent_result".equals(event.type()))
                .map(event -> event.agentId() + " (" + event.state() + "): " + event.message())
                .toList();
    }

    private static List<SourceReference> resultSources(List<TripEvent> events) {
        return events.stream()
                .filter(event -> "agent_result".equals(event.type()))
                .flatMap(event -> event.sources().stream())
                .distinct()
                .toList();
    }
}
