package com.vamsdest.agent;

import com.vamsdest.api.TripRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Coordinates the four specialist agents for one trip, then merges their briefs. Every progress event is written
 * to the {@link RunStore}, so pollers see the work as it happens, whichever process runs it.
 */
@Service
public class TripCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TripCoordinator.class);

    private final AgentHarness harness;
    private final RunStore store;
    private final Executor agentExecutor;
    private final int specialistSearches;
    private final int chatSearches;

    public TripCoordinator(
            AgentHarness harness,
            RunStore store,
            @Qualifier("agentExecutor") Executor agentExecutor,
            @Value("${vamsdest.agent.max-searches:4}") int specialistSearches,
            @Value("${vamsdest.agent.chat-max-searches:2}") int chatSearches) {
        this.harness = harness;
        this.store = store;
        this.agentExecutor = agentExecutor;
        this.specialistSearches = specialistSearches;
        this.chatSearches = chatSearches;
    }

    /** Runs all specialists in parallel, merges their findings, and records the final status. Blocks until done. */
    public void runTrip(String runId, TripRequest request) {
        List<AgentTask> tasks = List.of(
                new AgentTask("destination", "You are the Destination Agent for the VamsDest travel planning "
                        + "service. You research destinations: neighborhoods, cultural highlights, local transport, "
                        + "entry requirements, and practical visitor information.", destinationTask(request)),
                new AgentTask("stay", "You are the Stay & Activities Agent for the VamsDest travel planning "
                        + "service. You research date-specific weather, events, popular activities, hotels, room "
                        + "discounts, and package offers.", stayTask(request)),
                new AgentTask("transport", "You are the Flights & Transport Agent for the VamsDest travel "
                        + "planning service. You research flight options and flexible-date trade-offs.",
                        transportTask(request)),
                new AgentTask("taxi", "You are the Taxi & Car Agent for the VamsDest travel planning service. "
                        + "You research only ground transportation by taxi, ride-hail, airport transfer, and car "
                        + "rental. You do not research flights, hotels, or activities.", taxiTask(request)));
        CompletableFuture<?>[] futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(() -> execute(runId, task), agentExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();

        List<TripEvent> events = store.eventsAfter(runId, 0);
        boolean failed = events.stream().anyMatch(event -> "agent_result".equals(event.type())
                && "failed".equals(event.state()));
        String summary;
        try {
            summary = harness.run(
                    "You are the VamsDest Package Coordinator. You merge specialist research into one "
                            + "consolidated travel package recommendation.",
                    "Coordinate these specialist findings into one concise travel plan: a suggested day-by-day "
                            + "outline, where to stay, how to get there, and key trade-offs. Respect the "
                            + "traveler's preferences, keep the specialists' uncertainty, cite their source URLs, "
                            + "and call out anything a specialist could not find. Do not claim confirmed prices "
                            + "or availability.\n\nTraveler request: " + dateRange(request) + " in "
                            + request.destination() + ". Preferred travel style: " + budgetHint(request)
                            + "\n\nSpecialist findings:\n" + String.join("\n\n", briefings(events)),
                    0,
                    AgentHarness.AgentListener.NONE).answer();
        } catch (RuntimeException summaryFailure) {
            log.warn("trip {} coordinator failed: {}", runId, summaryFailure.getMessage());
            summary = "Trip research completed, but the combined recommendation could not be generated. "
                    + "Review each specialist's findings below.";
            failed = true;
        }
        store.append(runId, TripEvent.summary(runId, summary, resultSources(events)));
        String state = failed ? RunRecord.PARTIAL : RunRecord.COMPLETED;
        store.setStatus(runId, state);
        store.append(runId, TripEvent.complete(runId, state,
                failed ? "Research finished with one or more agent errors." : "All travel agents finished."));
    }

    /** Answers one follow-up question using the findings already recorded for this trip. */
    public String answer(String runId, TripRequest request, String message) {
        List<TripEvent> events = store.eventsAfter(runId, 0);
        String knownSources = resultSources(events).stream()
                .map(source -> "- " + source.title() + " (" + source.url() + ")")
                .collect(Collectors.joining("\n"));
        return harness.run(
                "You are the VamsDest Travel Assistant. You answer a traveler's follow-up questions about a trip "
                        + "your specialist agents already researched. Search again only when the existing "
                        + "findings do not answer the question.",
                "Trip destination: " + request.destination()
                        + "\nTravel dates: " + dateRange(request)
                        + "\nPreferred travel style: " + budgetHint(request)
                        + "\n\nSpecialist findings:\n" + String.join("\n\n", briefings(events))
                        + "\n\nSources already consulted:\n" + knownSources
                        + "\n\nTraveler follow-up question (answer this): " + message,
                chatSearches,
                AgentHarness.AgentListener.NONE).answer();
    }

    private void execute(String runId, AgentTask task) {
        store.append(runId, TripEvent.status(runId, task.id(), "running", "Planning research."));
        try {
            AgentHarness.AgentOutcome outcome = harness.run(task.role(), task.brief(), specialistSearches,
                    new AgentHarness.AgentListener() {
                        @Override
                        public void onSearch(String query) {
                            store.append(runId, TripEvent.tool(runId, task.id(), query));
                        }

                        @Override
                        public void onThinking() {
                            store.append(runId, TripEvent.status(runId, task.id(), "summarizing", "Reading results."));
                        }
                    });
            store.append(runId, TripEvent.result(runId, task.id(), "completed", outcome.answer(), outcome.sources()));
        } catch (RuntimeException exception) {
            log.warn("trip {} agent {} failed: {}", runId, task.id(), exception.getMessage());
            store.append(runId, TripEvent.result(runId, task.id(), "failed",
                    "This agent could not complete its research. Try again shortly.", List.of()));
        }
    }

    private static List<String> briefings(List<TripEvent> events) {
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

    private static String destinationTask(TripRequest request) {
        return "Research " + request.destination() + " for a trip " + dateRange(request)
                + ". Cover key neighborhoods, cultural highlights, local transport, entry or visa notes, and "
                + "practical visitor information. Finish with a short, sourced destination brief.";
    }

    private static String stayTask(TripRequest request) {
        return "For " + request.destination() + " from " + dateRange(request)
                + ", find weather forecasts or seasonal norms, events on those dates, popular activities, hotels, "
                + "room discounts, and package offers. Preferred travel style: " + budgetHint(request) + ". "
                + "Distinguish reported listings from bookable availability, and seasonal averages from dated "
                + "forecasts. Finish with a short, sourced stay & activities brief.";
    }

    private static String transportTask(TripRequest request) {
        LocalDate earliestDeparture = request.departureDate().minusDays(2);
        LocalDate latestDeparture = request.departureDate().plusDays(2);
        LocalDate earliestReturn = request.returnDate().minusDays(2);
        LocalDate latestReturn = request.returnDate().plusDays(2);
        String route = request.origin() == null || request.origin().isBlank()
                ? "to " + request.destination() + " (origin not given; cover main gateway airports)"
                : request.origin() + " to " + request.destination();
        return "Research flight options for " + route + ". Travel dates are " + dateRange(request)
                + "; compare shifting the whole trip up to two days earlier or later. "
                + "Outbound window: " + earliestDeparture + " through " + latestDeparture
                + "; return window: " + earliestReturn + " through " + latestReturn
                + ". Preferred travel style: " + budgetHint(request)
                + ". Report only source-backed options, not assumed prices or availability. "
                + "Finish with a short, sourced flights brief.";
    }

    private static String taxiTask(TripRequest request) {
        String pickup = request.origin() == null || request.origin().isBlank()
                ? "arrival airports and city-center pickups in " + request.destination()
                : "airport and city transfers between " + request.origin() + " and " + request.destination();
        return "Search only for taxi, ride-hail, airport transfer, and rental car options for " + pickup
                + ". Travel dates are " + dateRange(request)
                + ". Cover typical fare ranges, rental car companies and vehicle classes, and whether a "
                + "driving permit or minimum age applies. Preferred travel style: " + budgetHint(request)
                + ". Report only source-backed services and rates; mark anything you cannot verify for these "
                + "dates. Finish with a short, sourced taxi & car brief.";
    }

    private static String dateRange(TripRequest request) {
        return request.departureDate() + " through " + request.returnDate();
    }

    private static String budgetHint(TripRequest request) {
        return request.budget() == null || request.budget().isBlank() ? "unspecified" : request.budget();
    }

    private record AgentTask(String id, String role, String brief) {
    }
}
