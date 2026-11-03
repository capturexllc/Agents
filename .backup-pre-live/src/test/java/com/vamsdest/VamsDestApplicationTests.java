package com.vamsdest;

import com.vamsdest.agent.TripRunRegistry;
import com.vamsdest.api.TripRequest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VamsDestApplicationTests {
        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private TripRunRegistry tripRunRegistry;

    @Test
    void contextLoads() {
    }

        @Test
        void createsTripAndReturnsLiveEventsUrl() throws Exception {
                mockMvc.perform(post("/api/trips")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("""
                                                                {
                                                                    "destination": "Kyoto",
                                                                    "origin": "Seattle",
                                                                    "departureDate": "2030-05-01",
                                                                    "returnDate": "2030-05-07",
                                                                    "budget": "value"
                                                                }
                                                                """))
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
                    void tripRunsAreNotVisibleToAnotherOwner() {
                        TripRunRegistry.TripRun ownedTrip = tripRunRegistry.create(
                                new TripRequest("Kyoto", "Seattle", LocalDate.parse("2030-05-01"),
                                        LocalDate.parse("2030-05-07"), "value"),
                                "traveler-a");

                        assertSame(ownedTrip, tripRunRegistry.get(ownedTrip.id(), "traveler-a"));
                        assertNull(tripRunRegistry.get(ownedTrip.id(), "traveler-b"));
                    }
}
