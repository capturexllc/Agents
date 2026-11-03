package com.vamsdest.agent;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes one job. Shared by the local dispatcher and the AWS worker Lambda. Expected failures become events the
 * client can show; only infrastructure errors (such as DynamoDB being unreachable) are thrown, so the queue can retry.
 */
@Component
public class JobProcessor {
    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final RunStore store;
    private final TripCoordinator coordinator;

    public JobProcessor(RunStore store, TripCoordinator coordinator) {
        this.store = store;
        this.coordinator = coordinator;
    }

    public void process(Job job) {
        Optional<RunRecord> found = store.find(job.tripId());
        if (found.isEmpty()) {
            log.warn("job {} references unknown trip run", job.type());
            return;
        }
        RunRecord run = found.get();
        if (Job.TRIP.equals(job.type())) {
            runTrip(run);
        } else if (Job.CHAT.equals(job.type())) {
            answerChat(run, job);
        } else {
            log.warn("ignoring unknown job type {}", job.type());
        }
    }

    private void runTrip(RunRecord run) {
        // At-least-once queues can redeliver; a run that already finished is not re-researched.
        if (!RunRecord.PLANNING.equals(run.status())) {
            log.info("trip {} already {}, skipping redelivery", run.id(), run.status());
            return;
        }
        try {
            coordinator.runTrip(run.id(), run.request());
        } catch (RuntimeException exception) {
            log.error("trip {} stopped unexpectedly", run.id(), exception);
            store.setStatus(run.id(), RunRecord.PARTIAL);
            store.append(run.id(), TripEvent.complete(run.id(), RunRecord.PARTIAL,
                    "Research stopped unexpectedly. Try planning the trip again."));
        }
    }

    private void answerChat(RunRecord run, Job job) {
        try {
            String answer = coordinator.answer(run.id(), run.request(), job.question());
            store.append(run.id(), TripEvent.chatAnswer(run.id(), job.requestId(), "completed", answer,
                    List.of()));
        } catch (RuntimeException exception) {
            log.warn("chat for trip {} failed: {}", run.id(), exception.getMessage());
            store.append(run.id(), TripEvent.chatAnswer(run.id(), job.requestId(), "failed",
                    "The travel assistant could not respond. Try again shortly.", List.of()));
        }
    }
}
