package com.vamsdest.agent;

import com.vamsdest.api.TripRequest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class TripRunRegistry {
    private static final Duration RETENTION = Duration.ofHours(1);
    private final ConcurrentMap<String, TripRun> runs = new ConcurrentHashMap<>();

    public TripRun create(TripRequest request, String ownerId) {
        String id = java.util.UUID.randomUUID().toString();
        TripRun run = new TripRun(id, request, ownerId);
        runs.put(id, run);
        return run;
    }

    public TripRun get(String id, String ownerId) {
        TripRun run = runs.get(id);
        return run != null && run.ownerId().equals(ownerId) ? run : null;
    }

    @Scheduled(fixedDelay = 300_000)
    void removeExpiredRuns() {
        Instant cutoff = Instant.now().minus(RETENTION);
        runs.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }

    public static final class TripRun {
        private final String id;
        private final TripRequest request;
        private final String ownerId;
        private final Instant createdAt = Instant.now();
        private final List<TripEvent> events = new ArrayList<>();
        private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        private boolean complete;

        private TripRun(String id, TripRequest request, String ownerId) {
            this.id = id;
            this.request = request;
            this.ownerId = ownerId;
        }

        public String id() {
            return id;
        }

        public TripRequest request() {
            return request;
        }

        private String ownerId() {
            return ownerId;
        }

        private Instant createdAt() {
            return createdAt;
        }

        public synchronized boolean hasFailedAgent() {
            return events.stream().anyMatch(event -> "agent_result".equals(event.type())
                    && "failed".equals(event.state()));
        }

        public synchronized List<SourceReference> resultSources() {
            return events.stream()
                .filter(event -> "agent_result".equals(event.type()))
                .flatMap(event -> event.sources().stream())
                .distinct()
                .toList();
        }

        public synchronized List<String> agentBriefings() {
            return events.stream()
                    .filter(event -> "agent_result".equals(event.type()))
                    .map(event -> event.agentId() + " (" + event.state() + "): " + event.message())
                    .toList();
        }

        public synchronized void subscribe(SseEmitter emitter) {
            emitter.onCompletion(() -> subscribers.remove(emitter));
            emitter.onTimeout(() -> subscribers.remove(emitter));
            if (!complete) {
                subscribers.add(emitter);
            }
            events.forEach(event -> send(emitter, event));
            if (complete) {
                emitter.complete();
            }
        }

        public synchronized void publish(TripEvent event) {
            events.add(event);
            if ("trip_complete".equals(event.type())) {
                complete = true;
            }
            for (SseEmitter subscriber : subscribers) {
                send(subscriber, event);
                if (complete) {
                    subscriber.complete();
                }
            }
            if (complete) {
                subscribers.clear();
            }
        }

        private void send(SseEmitter emitter, TripEvent event) {
            try {
                emitter.send(SseEmitter.event().name(event.type()).data(event));
            } catch (IOException exception) {
                subscribers.remove(emitter);
            }
        }
    }
}