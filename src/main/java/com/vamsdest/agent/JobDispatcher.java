package com.vamsdest.agent;

/** Hands a job to whatever runs background work: an in-process executor locally, SQS on AWS. */
public interface JobDispatcher {
    void enqueue(Job job);
}
