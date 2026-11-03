package com.vamsdest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vamsdest.agent.InMemoryRunStore;
import com.vamsdest.agent.TravelResearchProvider;
import com.vamsdest.agent.TripEvent;
import com.vamsdest.api.TripRequest;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@SpringBootTest(properties = "vamsdest.search.api-key=test-only-key")
@AutoConfigureMockMvc
class VamsDestApplicationTests {
    private static final String TRIP_JSON = """
            {
                "destination": "Kyoto",
                "origin": "Seattle",
                "departureDate": "2030-05-01",
                "returnDate": "2030-05-07",
                "budget": "value"
            }
            """;

    @MockitoBean
    private BedrockRuntimeClient bedrockRuntimeClient;

    @MockitoBean
    private TravelResearchProvider travelResearchProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryRunStore runStore;

    @Test
    void contextLoads() {
    }

    @Test
    void createsTripAndReturnsPollingUrl() throws Exception {
        mockMvc.perform(post("/api/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRIP_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.tripId").isNotEmpty())
                .andExpect(jsonPath("$.eventsUrl").value(org.hamcrest.Matchers.containsString("/events")));
    }

    @Test
    void rejectsReturnDateThatIsNotAfterDeparture() throws Exception {
        mockMvc.perform(post("/api/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destination": "Kyoto",
                                    "departureDate": "2030-05-07",
                                    "returnDate": "2030-05-07"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void returnsOrderedEventsAfterCursor() throws Exception {
        String tripId = "local-events-test";
        runStore.create(tripId, "anonymous", new TripRequest("Kyoto", null, LocalDate.parse("2030-05-01"),
                LocalDate.parse("2030-05-07"), null), Instant.now());
        runStore.append(tripId, TripEvent.status(tripId, "stay", "running", "first"));
        runStore.append(tripId, TripEvent.status(tripId, "stay", "running", "second"));

        mockMvc.perform(get("/api/trips/" + tripId + "/events").param("after", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAfter").value(2))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].message").value("second"));
    }

    @Test
    void hidesTripsOwnedByAnotherUser() throws Exception {
        String tripId = "someone-elses-trip";
        runStore.create(tripId, "traveler-a", new TripRequest("Kyoto", null, LocalDate.parse("2030-05-01"),
                LocalDate.parse("2030-05-07"), null), Instant.now());

        mockMvc.perform(get("/api/trips/" + tripId + "/events"))
                .andExpect(status().isNotFound());
    }
}
