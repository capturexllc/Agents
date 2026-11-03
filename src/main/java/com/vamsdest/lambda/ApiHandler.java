package com.vamsdest.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vamsdest.api.ChatRequest;
import com.vamsdest.api.TripRequest;
import com.vamsdest.service.NotFoundException;
import com.vamsdest.service.TripService;
import com.vamsdest.service.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP API (API Gateway payload v2) entry point. The JWT authorizer runs before this handler and places the Cognito
 * subject in the request context, which is the only identity this handler trusts.
 */
public class ApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
    private static final Map<String, String> JSON_HEADERS = Map.of(
            "Content-Type", "application/json",
            "Cache-Control", "no-store");

    private final TripService trips;
    private final ObjectMapper mapper;

    /** Used by the Lambda runtime. */
    public ApiHandler() {
        this(LambdaWiring.tripService(), LambdaWiring.mapper());
    }

    ApiHandler(TripService trips, ObjectMapper mapper) {
        this.trips = trips;
        this.mapper = mapper;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            return route(event);
        } catch (ValidationException exception) {
            return json(400, Map.of("message", exception.getMessage()));
        } catch (NotFoundException exception) {
            return json(404, Map.of("message", exception.getMessage()));
        } catch (JsonProcessingException exception) {
            return json(400, Map.of("message", "Request body must be valid JSON."));
        } catch (RuntimeException exception) {
            log.error("unhandled API error", exception);
            return json(500, Map.of("message", "Something went wrong. Try again shortly."));
        }
    }

    private APIGatewayV2HTTPResponse route(APIGatewayV2HTTPEvent event) throws JsonProcessingException {
        String owner = subject(event);
        if (owner == null) {
            return json(401, Map.of("message", "Sign in required."));
        }
        String routeKey = event.getRouteKey();
        if ("POST /api/trips".equals(routeKey)) {
            TripRequest request = mapper.readValue(body(event), TripRequest.class);
            return json(202, trips.createTrip(owner, request));
        }
        if ("GET /api/trips/{tripId}/events".equals(routeKey)) {
            return json(200, trips.events(owner, path(event, "tripId"), afterParam(event)));
        }
        if ("POST /api/trips/{tripId}/chat".equals(routeKey)) {
            ChatRequest request = mapper.readValue(body(event), ChatRequest.class);
            String requestId = trips.requestChat(owner, path(event, "tripId"), request);
            return json(202, Map.of("requestId", requestId));
        }
        return json(404, Map.of("message", "Not found."));
    }

    private static String subject(APIGatewayV2HTTPEvent event) {
        if (event.getRequestContext() == null || event.getRequestContext().getAuthorizer() == null
                || event.getRequestContext().getAuthorizer().getJwt() == null) {
            return null;
        }
        Map<String, String> claims = event.getRequestContext().getAuthorizer().getJwt().getClaims();
        String subject = claims == null ? null : claims.get("sub");
        return subject == null || subject.isBlank() ? null : subject;
    }

    private String body(APIGatewayV2HTTPEvent event) {
        String body = event.getBody();
        if (body == null || body.isBlank()) {
            throw new ValidationException("Request body is required.");
        }
        return Boolean.TRUE.equals(event.getIsBase64Encoded())
                ? new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8)
                : body;
    }

    private static String path(APIGatewayV2HTTPEvent event, String name) {
        String value = event.getPathParameters() == null ? null : event.getPathParameters().get(name);
        if (value == null || value.isBlank()) {
            throw new NotFoundException("Trip not found.");
        }
        return value;
    }

    private static long afterParam(APIGatewayV2HTTPEvent event) {
        Map<String, String> query = event.getQueryStringParameters();
        String value = query == null ? null : query.get("after");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long after = Long.parseLong(value.strip());
            if (after < 0) {
                throw new ValidationException("after must be a non-negative number.");
            }
            return after;
        } catch (NumberFormatException exception) {
            throw new ValidationException("after must be a non-negative number.");
        }
    }

    private APIGatewayV2HTTPResponse json(int status, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(status)
                    .withHeaders(JSON_HEADERS)
                    .withBody(mapper.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode response", exception);
        }
    }
}
