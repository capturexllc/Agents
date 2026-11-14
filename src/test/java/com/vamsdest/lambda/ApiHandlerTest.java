package com.vamsdest.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vamsdest.agent.Job;
import com.vamsdest.agent.JobDispatcher;
import com.vamsdest.agent.InMemoryRunStore;
import com.vamsdest.service.TripService;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiHandlerTest {
    private static final String TRIP_BODY = """
            {"destination":"Kyoto","origin":"Seattle","departureDate":"2030-05-01","returnDate":"2030-05-07"}
            """;

    private final ObjectMapper mapper = LambdaWiring.mapper();
    private final List<Job> queued = new ArrayList<>();
    private ApiHandler handler;

    @BeforeEach
    void setUp() {
        JobDispatcher capture = queued::add;
        TripService service = new TripService(new InMemoryRunStore(), capture,
                Validation.buildDefaultValidatorFactory().getValidator());
        handler = new ApiHandler(service, mapper);
    }

    @Test
    void createsTripForSignedInUserAndQueuesResearch() throws Exception {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("POST /api/trips", "traveler-a", TRIP_BODY, null, null), (Context) null);

        assertEquals(202, response.getStatusCode());
        assertTrue(response.getBody().contains("\"tripId\""));
        assertEquals(1, queued.size());
        assertEquals(Job.TRIP, queued.get(0).type());
    }

    @Test
    void acceptsRequestsWithoutSignIn() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("POST /api/trips", null, TRIP_BODY, null, null), (Context) null);

        assertEquals(202, response.getStatusCode());
        assertEquals(1, queued.size());
    }

    @Test
    void returnsBadRequestForInvalidTripDates() {
        String body = "{\"destination\":\"Kyoto\",\"departureDate\":\"2030-05-07\",\"returnDate\":\"2030-05-07\"}";

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("POST /api/trips", "traveler-a", body, null, null), (Context) null);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Return date must be after departure date."));
        assertTrue(queued.isEmpty());
    }

    @Test
    void returnsBadRequestForMalformedJson() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("POST /api/trips", "traveler-a", "{not json", null, null), (Context) null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void anyCallerCanReadTheSharedTrip() {
        String tripId = "trip-shared";
        handler = new ApiHandler(new TripService(seededStore(tripId, ApiHandler.ANONYMOUS_OWNER), queued::add,
                Validation.buildDefaultValidatorFactory().getValidator()), mapper);

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("GET /api/trips/{tripId}/events", null, null, Map.of("tripId", tripId), null),
                (Context) null);

        assertEquals(200, response.getStatusCode());
    }

    @Test
    void rejectsNonNumericEventCursor() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("GET /api/trips/{tripId}/events", "traveler-a", null, Map.of("tripId", "x"),
                        Map.of("after", "abc")), (Context) null);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void queuesChatQuestionAndReturnsRequestId() {
        String tripId = "trip-chat";
        handler = new ApiHandler(new TripService(seededStore(tripId, ApiHandler.ANONYMOUS_OWNER), queued::add,
                Validation.buildDefaultValidatorFactory().getValidator()), mapper);

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("POST /api/trips/{tripId}/chat", "traveler-a", "{\"message\":\"Need a taxi?\"}",
                        Map.of("tripId", tripId), null), (Context) null);

        assertEquals(202, response.getStatusCode());
        assertTrue(response.getBody().contains("requestId"));
        assertEquals(Job.CHAT, queued.get(0).type());
        assertEquals("Need a taxi?", queued.get(0).question());
    }

    @Test
    void returnsNotFoundForUnknownRoute() {
        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("DELETE /api/trips/{tripId}", "traveler-a", null, Map.of("tripId", "x"), null),
                (Context) null);

        assertEquals(404, response.getStatusCode());
    }

    private static InMemoryRunStore seededStore(String tripId, String owner) {
        InMemoryRunStore store = new InMemoryRunStore();
        store.create(tripId, owner, new com.vamsdest.api.TripRequest("Kyoto", null,
                java.time.LocalDate.parse("2030-05-01"), java.time.LocalDate.parse("2030-05-07"), null),
                java.time.Instant.now());
        return store;
    }

    private static APIGatewayV2HTTPEvent event(String routeKey, String subject, String body,
                                               Map<String, String> pathParameters,
                                               Map<String, String> query) {
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setRouteKey(routeKey);
        event.setBody(body);
        event.setPathParameters(pathParameters);
        event.setQueryStringParameters(query);
        APIGatewayV2HTTPEvent.RequestContext context = new APIGatewayV2HTTPEvent.RequestContext();
        if (subject != null) {
            APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer = new APIGatewayV2HTTPEvent.RequestContext.Authorizer();
            APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt = new APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT();
            jwt.setClaims(Map.of("sub", subject));
            authorizer.setJwt(jwt);
            context.setAuthorizer(authorizer);
        }
        event.setRequestContext(context);
        return event;
    }
}
