package com.vamsdest.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vamsdest.api.TripRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;

class TripCoordinatorTest {
    private static final TripRequest TRIP = new TripRequest("Kyoto", "Seattle", LocalDate.parse("2030-05-01"),
            LocalDate.parse("2030-05-07"), "value");
    private static final String RUN = "run-1";

    private ExecutorService specialists;
    private InMemoryRunStore store;

    @BeforeEach
    void setUp() {
        specialists = Executors.newFixedThreadPool(4);
        store = new InMemoryRunStore();
    }

    @AfterEach
    void tearDown() {
        specialists.shutdownNow();
    }

    @Test
    void runsFourSpecialistsThenCoordinatorAndCompletes() {
        store.create(RUN, "traveler", TRIP, java.time.Instant.now());
        TripCoordinator coordinator = coordinator(new ScriptedBedrock(false));

        coordinator.runTrip(RUN, TRIP);

        List<TripEvent> events = store.eventsAfter(RUN, 0);
        List<String> completedAgents = events.stream()
                .filter(event -> "agent_result".equals(event.type()) && "completed".equals(event.state()))
                .map(TripEvent::agentId)
                .toList();
        assertEquals(4, completedAgents.size());
        assertTrue(completedAgents.containsAll(List.of("destination", "stay", "transport", "taxi")));
        assertTrue(events.stream().anyMatch(event -> "trip_summary".equals(event.type())));
        assertEquals("completed", store.find(RUN).orElseThrow().status());
        TripEvent last = events.get(events.size() - 1);
        assertEquals("trip_complete", last.type());
        assertEquals("completed", last.state());
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1, events.get(index).seq());
        }
    }

    @Test
    void marksRunPartialWhenSpecialistsFail() {
        store.create(RUN, "traveler", TRIP, java.time.Instant.now());
        TripCoordinator coordinator = coordinator(new ScriptedBedrock(true));

        coordinator.runTrip(RUN, TRIP);

        long failed = store.eventsAfter(RUN, 0).stream()
                .filter(event -> "agent_result".equals(event.type()) && "failed".equals(event.state()))
                .count();
        assertEquals(4, failed);
        assertEquals("partial", store.find(RUN).orElseThrow().status());
        TripEvent last = store.eventsAfter(RUN, 0).get(store.eventsAfter(RUN, 0).size() - 1);
        assertEquals("partial", last.state());
    }

    @Test
    void answersFollowUpFromRecordedFindings() {
        store.create(RUN, "traveler", TRIP, java.time.Instant.now());
        TripCoordinator coordinator = coordinator(new ScriptedBedrock(false));
        coordinator.runTrip(RUN, TRIP);

        String answer = coordinator.answer(RUN, TRIP, "Is a car needed?");

        assertEquals("specialist reply", answer);
    }

    private TripCoordinator coordinator(BedrockRuntimeClient bedrock) {
        AgentHarness harness = new AgentHarness(bedrock, query -> List.of(), "test-model", "", "", 4, 500);
        return new TripCoordinator(harness, store, specialists, 2, 2);
    }

    /** Answers every model call with plain text, or fails every call to simulate a Bedrock outage. */
    private static final class ScriptedBedrock implements BedrockRuntimeClient {
        private final boolean fail;

        private ScriptedBedrock(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            if (fail) {
                throw new IllegalStateException("bedrock unavailable");
            }
            return ConverseResponse.builder()
                    .stopReason(StopReason.END_TURN)
                    .output(ConverseOutput.fromMessage(Message.builder()
                            .role(ConversationRole.ASSISTANT)
                            .content(ContentBlock.fromText("specialist reply"))
                            .build()))
                    .build();
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
        }
    }
}
