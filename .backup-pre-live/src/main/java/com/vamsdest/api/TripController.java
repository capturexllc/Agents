package com.vamsdest.api;

import com.vamsdest.agent.TripCoordinator;
import com.vamsdest.agent.TripRunRegistry;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/trips")
public class TripController {
    private final TripRunRegistry registry;
    private final TripCoordinator coordinator;

    public TripController(TripRunRegistry registry, TripCoordinator coordinator) {
        this.registry = registry;
        this.coordinator = coordinator;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody TripRequest request) {
        if (!request.returnDate().isAfter(request.departureDate())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Return date must be after departure date."));
        }
        TripRunRegistry.TripRun run = registry.create(request, currentUserId());
        coordinator.start(run);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new TripCreated(run.id(), "/api/trips/" + run.id() + "/events"));
    }

    @PostMapping("/{tripId}/chat")
    public ResponseEntity<?> chat(@PathVariable String tripId, @Valid @RequestBody ChatRequest request) {
        TripRunRegistry.TripRun run = registry.get(tripId, currentUserId());
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("message", coordinator.answer(run, request.message())));
    }

    @GetMapping(value = "/{tripId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String tripId) {
        TripRunRegistry.TripRun run = registry.get(tripId, currentUserId());
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());
        run.subscribe(emitter);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidRequest(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid trip request.");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    private static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "anonymous" : authentication.getName();
    }
}