package com.vamsdest.api;

import com.vamsdest.service.NotFoundException;
import com.vamsdest.service.TripService;
import com.vamsdest.service.TripService.EventsPage;
import com.vamsdest.service.ValidationException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Local development API. The AWS deployment serves the same routes from {@code ApiHandler}. */
@RestController
@RequestMapping("/api/trips")
public class TripController {
    private final TripService trips;

    public TripController(TripService trips) {
        this.trips = trips;
    }

    @PostMapping
    public ResponseEntity<TripCreated> create(@RequestBody TripRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(trips.createTrip(currentUserId(), request));
    }

    @PostMapping("/{tripId}/chat")
    public ResponseEntity<Map<String, String>> chat(@PathVariable String tripId, @RequestBody ChatRequest request) {
        String requestId = trips.requestChat(currentUserId(), tripId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("requestId", requestId));
    }

    @GetMapping("/{tripId}/events")
    public ResponseEntity<EventsPage> events(@PathVariable String tripId,
                                             @RequestParam(name = "after", defaultValue = "0") long after) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(trips.events(currentUserId(), tripId, after));
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<Map<String, String>> invalid(ValidationException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    /** Local development only: anonymous callers share one owner so trips stay reachable in that mode. */
    private static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return "anonymous";
        }
        return authentication.getName();
    }
}
