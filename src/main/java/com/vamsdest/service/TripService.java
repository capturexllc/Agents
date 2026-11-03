package com.vamsdest.service;

import com.vamsdest.agent.Job;
import com.vamsdest.agent.JobDispatcher;
import com.vamsdest.agent.RunRecord;
import com.vamsdest.agent.RunStore;
import com.vamsdest.agent.TripEvent;
import com.vamsdest.api.ChatRequest;
import com.vamsdest.api.TripCreated;
import com.vamsdest.api.TripRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Transport-neutral trip operations used by both the Spring controller (local) and the API Lambda (AWS). Work is
 * queued rather than run inline, so every request returns quickly and clients poll for progress.
 */
@Service
public class TripService {
    private final RunStore store;
    private final JobDispatcher dispatcher;
    private final Validator validator;

    public TripService(RunStore store, JobDispatcher dispatcher, Validator validator) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.validator = validator;
    }

    public TripCreated createTrip(String ownerId, TripRequest request) {
        requireValid(request);
        if (!request.returnDate().isAfter(request.departureDate())) {
            throw new ValidationException("Return date must be after departure date.");
        }
        String tripId = UUID.randomUUID().toString();
        store.create(tripId, ownerId, request, Instant.now());
        dispatcher.enqueue(Job.trip(tripId));
        return new TripCreated(tripId, "/api/trips/" + tripId + "/events");
    }

    public EventsPage events(String ownerId, String tripId, long after) {
        RunRecord run = owned(ownerId, tripId);
        long from = Math.max(after, 0);
        List<TripEvent> events = store.eventsAfter(tripId, from);
        long nextAfter = events.isEmpty() ? from : events.get(events.size() - 1).seq();
        return new EventsPage(run.status(), nextAfter, events);
    }

    /** Queues a follow-up question. The answer arrives as a {@code chat_answer} event with this request id. */
    public String requestChat(String ownerId, String tripId, ChatRequest request) {
        requireValid(request);
        owned(ownerId, tripId);
        String requestId = UUID.randomUUID().toString();
        dispatcher.enqueue(Job.chat(tripId, requestId, request.message().strip()));
        return requestId;
    }

    private RunRecord owned(String ownerId, String tripId) {
        return store.findOwned(tripId, ownerId).orElseThrow(() -> new NotFoundException("Trip not found."));
    }

    private <T> void requireValid(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            ConstraintViolation<T> first = violations.iterator().next();
            throw new ValidationException(first.getPropertyPath() + " " + first.getMessage());
        }
    }

    public record EventsPage(String status, long nextAfter, List<TripEvent> events) {
    }
}
