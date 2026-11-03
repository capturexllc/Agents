package com.vamsdest.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vamsdest.agent.Job;
import com.vamsdest.agent.JobProcessor;
import java.util.concurrent.ExecutorService;

/**
 * SQS-triggered worker. Batch size is 1, so one message is one run. Infrastructure failures throw, which returns
 * the message to the queue; after repeated failures it moves to the dead-letter queue.
 */
public class WorkerHandler implements RequestHandler<SQSEvent, Void> {
    private final JobProcessor processor;
    private final ObjectMapper mapper;

    /** Used by the Lambda runtime. */
    public WorkerHandler() {
        ExecutorService specialists = LambdaWiring.specialistPool();
        this.processor = LambdaWiring.jobProcessor(specialists);
        this.mapper = LambdaWiring.mapper();
    }

    WorkerHandler(JobProcessor processor, ObjectMapper mapper) {
        this.processor = processor;
        this.mapper = mapper;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            processor.process(decode(message.getBody()));
        }
        return null;
    }

    private Job decode(String body) {
        try {
            return mapper.readValue(body, Job.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Job message is not valid JSON", exception);
        }
    }
}
