package com.vamsdest.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vamsdest.agent.Job;
import com.vamsdest.agent.JobDispatcher;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Sends jobs to the SQS queue that triggers the worker Lambda. */
final class SqsJobDispatcher implements JobDispatcher {
    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper mapper;

    SqsJobDispatcher(SqsClient sqs, String queueUrl, ObjectMapper mapper) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.mapper = mapper;
    }

    @Override
    public void enqueue(Job job) {
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(mapper.writeValueAsString(job))
                    .build());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode job", exception);
        }
    }
}
