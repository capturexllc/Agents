package com.vamsdest.agent;

import com.vamsdest.api.TripRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The trip workflow as a LangGraph4j graph: {@code research} then {@code coordinate}.
 *
 * <p>The four specialists run in parallel inside {@code research}. Each one is a ReAct agent. They communicate through
 * shared graph state: each specialist's briefing is appended to {@code briefings}, and {@code coordinate} reads every
 * briefing from that state to write the combined plan. Each step also writes run events, so the browser can show
 * progress while the graph runs.
 */
public final class TripGraph {
    static final String RESEARCH = "research";
    static final String COORDINATE = "coordinate";
    private static final Logger log = LoggerFactory.getLogger(TripGraph.class);

    /** A specialist's finding, shared with the coordinator through graph state. */
    public record Briefing(String agentId, String state, String message, List<SourceReference> sources)
            implements java.io.Serializable {
    }

    /** Everything one run needs. Passed in the graph input, so one compiled graph serves every trip. */
    public record RunContext(String runId, RunStore store, TripRequest request, RuntimeSettings settings,
            ReactAgent agent, PromptCatalog prompts) {
    }

    /** The final result of a trip run. */
    public record Result(String summary, boolean failed, List<SourceReference> sources) {
    }

    public static final class State extends AgentState {
        public State(Map<String, Object> data) {
            super(data);
        }

        RunContext context() {
            return RunRegistry.get(this.<String>value("contextKey").orElse(""), RunContext.class);
        }

        List<Briefing> briefings() {
            return this.<List<Briefing>>value("briefings").orElse(List.of());
        }

        int failures() {
            return this.<Integer>value("failures").orElse(0);
        }

        String summary() {
            return this.<String>value("summary").orElse("");
        }
    }

    private static final Map<String, Channel<?>> CHANNELS = Map.of(
            "contextKey", Channels.base(() -> ""),
            "briefings", Channels.<Briefing>appender(ArrayList::new),
            "failures", Channels.base(() -> 0),
            "summary", Channels.base(() -> ""));

    private final CompiledGraph<State> graph;
    private final Executor specialistExecutor;

    public TripGraph(Executor specialistExecutor) {
        this.specialistExecutor = specialistExecutor;
        this.graph = build();
    }

    /** Runs the whole workflow for one trip. Blocks until the coordinator has finished. */
    public Result run(RunContext context) {
        String contextKey = RunRegistry.register(context);
        try {
            State finished = graph.invoke(Map.<String, Object>of("contextKey", contextKey))
                    .orElseThrow(() -> new IllegalStateException("trip graph returned no state"));
            List<SourceReference> sources = finished.briefings().stream()
                    .flatMap(briefing -> briefing.sources().stream())
                    .distinct()
                    .toList();
            return new Result(finished.summary(), finished.failures() > 0, sources);
        } finally {
            RunRegistry.release(contextKey);
        }
    }

    private CompiledGraph<State> build() {
        try {
            StateGraph<State> definition = new StateGraph<>(CHANNELS, State::new);
            definition.addNode(RESEARCH, AsyncNodeAction.node_async(this::research));
            definition.addNode(COORDINATE, AsyncNodeAction.node_async(this::coordinate));
            definition.addEdge(StateGraph.START, RESEARCH);
            definition.addEdge(RESEARCH, COORDINATE);
            definition.addEdge(COORDINATE, StateGraph.END);
            return definition.compile(CompileConfig.builder().build());
        } catch (GraphStateException exception) {
            throw new IllegalStateException("Invalid trip graph", exception);
        }
    }

    private Map<String, Object> research(State state) {
        RunContext context = state.context();
        List<CompletableFuture<Briefing>> running = specialists(context).stream()
                .map(specialist -> CompletableFuture.supplyAsync(() -> runSpecialist(context, specialist),
                        specialistExecutor))
                .toList();
        List<Briefing> briefings = running.stream().map(CompletableFuture::join).toList();
        long failed = briefings.stream().filter(briefing -> "failed".equals(briefing.state())).count();
        return Map.of("briefings", briefings, "failures", (int) failed);
    }

    private Map<String, Object> coordinate(State state) {
        RunContext context = state.context();
        int failures = state.failures();
        String summary;
        try {
            String task = context.prompts().prompt("task-coordinator", Map.of(
                    "dateRange", dateRange(context.request()),
                    "destination", context.request().destination(),
                    "budget", budgetHint(context.request()),
                    "briefings", String.join("\n\n", briefingLines(state.briefings()))));
            summary = context.agent().run(
                    ReactAgent.systemPrompt(context.prompts(), "role-coordinator", 0),
                    task, 0, AgentListener.NONE, context.settings()).answer();
        } catch (RuntimeException failure) {
            log.warn("trip {} coordinator failed: {}", context.runId(), failure.getMessage());
            summary = "Trip research completed, but the combined recommendation could not be generated. "
                    + "Review each specialist's findings below.";
            failures++;
        }
        return Map.of("summary", summary, "failures", failures);
    }

    private Briefing runSpecialist(RunContext context, Specialist specialist) {
        RunStore store = context.store();
        String runId = context.runId();
        store.append(runId, TripEvent.status(runId, specialist.id(), "running", "Planning research."));
        AgentListener listener = new AgentListener() {
            @Override
            public void onSearch(String query) {
                store.append(runId, TripEvent.tool(runId, specialist.id(), query));
            }

            @Override
            public void onThinking() {
                store.append(runId, TripEvent.status(runId, specialist.id(), "summarizing", "Reading results."));
            }
        };
        try {
            ReactAgent.AgentOutcome outcome = context.agent().run(
                    ReactAgent.systemPrompt(context.prompts(), specialist.roleName(), context.settings().specialistSearches()),
                    specialist.task(), context.settings().specialistSearches(), listener, context.settings());
            store.append(runId, TripEvent.result(runId, specialist.id(), "completed", outcome.answer(),
                    outcome.sources()));
            return new Briefing(specialist.id(), "completed", outcome.answer(), outcome.sources());
        } catch (RuntimeException failure) {
            log.warn("trip {} agent {} failed: {}", runId, specialist.id(), failure.getMessage());
            String message = "This agent could not complete its research. Try again shortly.";
            store.append(runId, TripEvent.result(runId, specialist.id(), "failed", message, List.of()));
            return new Briefing(specialist.id(), "failed", message, List.of());
        }
    }

    /** The four specialists, with their task text rendered from the prompt catalog. */
    private List<Specialist> specialists(RunContext context) {
        TripRequest request = context.request();
        PromptCatalog prompts = context.prompts();
        return List.of(
                new Specialist("destination", "role-destination", prompts.prompt("task-destination", Map.of(
                        "destination", request.destination(), "dateRange", dateRange(request)))),
                new Specialist("stay", "role-stay", prompts.prompt("task-stay", Map.of(
                        "destination", request.destination(), "dateRange", dateRange(request),
                        "budget", budgetHint(request)))),
                new Specialist("transport", "role-transport", prompts.prompt("task-transport", Map.of(
                        "route", route(request), "dateRange", dateRange(request),
                        "outboundWindow", window(request.departureDate()),
                        "returnWindow", window(request.returnDate()), "budget", budgetHint(request)))),
                new Specialist("taxi", "role-taxi", prompts.prompt("task-taxi", Map.of(
                        "pickup", pickup(request), "dateRange", dateRange(request), "budget", budgetHint(request)))));
    }

    /** Lines of the form "agent (state): message", as the coordinator and chat prompts show them. */
    static List<String> briefingLines(List<Briefing> briefings) {
        return briefings.stream()
                .map(briefing -> briefing.agentId() + " (" + briefing.state() + "): " + briefing.message())
                .toList();
    }

    static String dateRange(TripRequest request) {
        return request.departureDate() + " through " + request.returnDate();
    }

    static String budgetHint(TripRequest request) {
        return request.budget() == null || request.budget().isBlank() ? "unspecified" : request.budget();
    }

    private static String window(LocalDate date) {
        return date.minusDays(2) + " through " + date.plusDays(2);
    }

    private static String route(TripRequest request) {
        return request.origin() == null || request.origin().isBlank()
                ? "to " + request.destination() + " (origin not given; cover main gateway airports)"
                : request.origin() + " to " + request.destination();
    }

    private static String pickup(TripRequest request) {
        return request.origin() == null || request.origin().isBlank()
                ? "arrival airports and city-center pickups in " + request.destination()
                : "airport and city transfers between " + request.origin() + " and " + request.destination();
    }

    private record Specialist(String id, String roleName, String task) {
    }
}
