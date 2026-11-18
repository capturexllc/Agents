package com.vamsdest.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vamsdest.api.TripRequest;
import com.vamsdest.rag.ChunkStore;
import com.vamsdest.rag.PolicyRetriever;
import com.vamsdest.rag.VectorIndex;
import java.time.Clock;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TripCoordinatorTest {
    private static final TripRequest TRIP = new TripRequest("Kyoto", "Seattle", LocalDate.parse("2030-05-01"),
            LocalDate.parse("2030-05-07"), "value");
    private static final String RUN = "run-1";
    private static final RuntimeSettings SETTINGS = new RuntimeSettings("model", 500, 8, 4, 2, 50, 10, 5, 30);

    private ExecutorService executor;
    private InMemoryRunStore store;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        store = new InMemoryRunStore();
        store.create(RUN, "anonymous", TRIP, Instant.now());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void fourSpecialistsShareBriefingsThenTheCoordinatorCompletes() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                String system = request.messages().get(0).toString();
                String text = system.contains("Package Coordinator")
                        ? "Consolidated plan from the briefings."
                        : "Specialist brief.";
                return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
            }
        };
        TripCoordinator coordinator = coordinator(model);

        coordinator.runTrip(RUN, TRIP);

        List<TripEvent> events = store.eventsAfter(RUN, 0);
        List<String> completed = events.stream()
                .filter(event -> "agent_result".equals(event.type()) && "completed".equals(event.state()))
                .map(TripEvent::agentId)
                .toList();
        assertEquals(4, completed.size());
        assertTrue(completed.containsAll(List.of("destination", "stay", "transport", "taxi")));
        assertEquals(5, calls.get(), "four specialists plus one coordinator call");
        assertTrue(events.stream().anyMatch(event -> "trip_summary".equals(event.type())));
        assertEquals("completed", store.find(RUN).orElseThrow().status());
        TripEvent last = events.get(events.size() - 1);
        assertEquals("trip_complete", last.type());
        assertEquals("completed", last.state());
    }

    @Test
    void whenEveryModelCallFailsTheRunEndsPartialWithFailureEvents() {
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new IllegalStateException("bedrock down");
            }
        };

        coordinator(broken).runTrip(RUN, TRIP);

        List<TripEvent> events = store.eventsAfter(RUN, 0);
        long failed = events.stream()
                .filter(event -> "agent_result".equals(event.type()) && "failed".equals(event.state()))
                .count();
        assertEquals(4, failed);
        assertEquals("partial", store.find(RUN).orElseThrow().status());
        assertEquals("partial", events.get(events.size() - 1).state());
    }

    private TripCoordinator coordinator(ChatModel model) {
        ModelFactory factory = new ModelFactory(null, "ca-central-1", "", "") {
            @Override
            public ChatModel create(String modelId) {
                return model;
            }
        };
        PromptCatalog prompts = new PromptCatalog(null, "", "", Duration.ofMinutes(5));
        PolicyRetriever policies = new PolicyRetriever(text -> new float[] {1f}, VectorIndex.EMPTY, ChunkStore.EMPTY);
        return new TripCoordinator(factory, (query -> List.of()), policies, store, executor, prompts, SETTINGS);
    }
}
