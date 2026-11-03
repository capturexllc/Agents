package com.vamsdest.agent;

import com.vamsdest.api.TripRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class TripCoordinator {
    private final TravelResearchProvider researchProvider;
    private final BedrockAdvisor advisor;
    private final Executor agentExecutor;

    public TripCoordinator(
            TravelResearchProvider researchProvider,
            BedrockAdvisor advisor,
            @Qualifier("agentExecutor") Executor agentExecutor) {
        this.researchProvider = researchProvider;
        this.advisor = advisor;
        this.agentExecutor = agentExecutor;
    }

    public void start(TripRunRegistry.TripRun run) {
        TripRequest request = run.request();
        List<AgentTask> tasks = List.of(
                new AgentTask("destination", "Destination Agent", destinationQuery(request)),
                new AgentTask("stay", "Stay & Activities Agent", stayQuery(request)),
                new AgentTask("transport", "Flights & Transport Agent", transportQuery(request)));
        CompletableFuture<?>[] futures = tasks.stream()
                .map(task -> CompletableFuture.runAsync(() -> execute(run, task), agentExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).whenComplete((ignored, exception) -> {
            boolean failed = run.hasFailedAgent();
            String summary;
            try {
            String requestSummary = "Coordinate these specialist findings into one concise travel plan. "
                + "Compare trade-offs, respect the traveler's preferences, retain uncertainty, and cite sources. "
                + "Do not claim confirmed prices or availability and do not book anything.\n"
                + "Traveler request: " + dateRange(request) + " in " + request.destination()
                + ". Preferred travel style: " + budgetHint(request)
                + "\nSpecialist findings:\n" + String.join("\n\n", run.agentBriefings());
            summary = advisor.summarize("VamsDest Package Coordinator", requestSummary, run.resultSources());
            } catch (RuntimeException summaryFailure) {
            summary = "Trip research completed, but the combined recommendation could not be generated. "
                + "Review each specialist's findings below.";
            failed = true;
            }
            run.publish(TripEvent.summary(run.id(), summary, run.resultSources()));
            run.publish(TripEvent.complete(run.id(), failed ? "partial" : "completed",
                    failed ? "Research finished with one or more agent errors." : "All travel agents finished."));
        });
    }

    public String answer(TripRunRegistry.TripRun run, String message) {
        List<SourceReference> sources = run.resultSources();
        return advisor.summarize("VamsDest Travel Assistant",
                "Traveler follow-up question: " + message + "\nTrip destination: " + run.request().destination()
                + "\nTravel dates: " + dateRange(run.request())
                + "\nPreferred travel style: " + budgetHint(run.request()),
                sources);
    }

    private void execute(TripRunRegistry.TripRun run, AgentTask task) {
        run.publish(TripEvent.status(run.id(), task.id(), "running", "Searching travel sources."));
        try {
            List<SourceReference> sources = researchProvider.search(task.query());
            run.publish(TripEvent.status(run.id(), task.id(), "summarizing", "Preparing sourced findings."));
            String summary = advisor.summarize(task.name(), task.query(), sources);
            run.publish(TripEvent.result(run.id(), task.id(), "completed", summary, sources));
        } catch (RuntimeException exception) {
            run.publish(TripEvent.result(run.id(), task.id(), "failed",
                    "This agent could not complete its research. Check provider configuration and try again.", List.of()));
        }
    }

    private static String destinationQuery(TripRequest request) {
        return "Travel destination guide for " + request.destination() + " for "
                + dateRange(request) + ": official tourism guidance, key neighborhoods, cultural highlights, "
                + "local transport, and practical visitor information. Do not invent live details.";
    }

    private static String stayQuery(TripRequest request) {
        return "For " + request.destination() + " from " + dateRange(request)
                + ", find weather forecasts, events, popular activities, hotel room discounts, and package offers. "
                + "Preferred travel style: " + budgetHint(request) + ". "
                + "Distinguish reported listings from bookable availability and dated weather forecasts.";
    }

    private static String transportQuery(TripRequest request) {
        LocalDate earliestDeparture = request.departureDate().minusDays(2);
        LocalDate latestDeparture = request.departureDate().plusDays(2);
        LocalDate earliestReturn = request.returnDate().minusDays(2);
        LocalDate latestReturn = request.returnDate().plusDays(2);
        String route = request.origin() == null || request.origin().isBlank()
                ? "to " + request.destination()
                : request.origin() + " to " + request.destination();
        return "Research flight options and rental cars for " + route + ". Travel dates are " + dateRange(request)
            + "; compare trip dates by shifting the whole trip up to two days earlier or later. "
            + "Outbound date window: " + earliestDeparture + " through " + latestDeparture
            + "; return date window: " + earliestReturn + " through " + latestReturn
                + ". Preferred travel style: " + budgetHint(request)
                + ". Report only source-backed options, not assumed prices or availability.";
    }

    private static String dateRange(TripRequest request) {
        return request.departureDate() + " through " + request.returnDate();
    }

    private static String budgetHint(TripRequest request) {
        return request.budget() == null || request.budget().isBlank() ? "unspecified" : request.budget();
    }

    private record AgentTask(String id, String name, String query) {
    }
}