package com.vamsdest.agent;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * Runs jobs on their own thread pool, separate from the specialist-agent pool. Sharing one bounded pool would let
 * trip jobs block while waiting for specialist tasks that never get a thread.
 */
@Component
public class LocalJobDispatcher implements JobDispatcher {
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final JobProcessor processor;

    public LocalJobDispatcher(JobProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void enqueue(Job job) {
        executor.execute(() -> processor.process(job));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
