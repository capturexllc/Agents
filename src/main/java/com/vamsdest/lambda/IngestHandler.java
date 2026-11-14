package com.vamsdest.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.vamsdest.rag.IngestService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Ingests documents uploaded to the policy corpus prefix (S3 ObjectCreated events). */
public class IngestHandler implements RequestHandler<S3Event, String> {
    private final IngestService ingest;

    /** Used by the Lambda runtime. */
    public IngestHandler() {
        this(LambdaWiring.ingestService());
    }

    IngestHandler(IngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        int chunks = 0;
        for (S3Event.S3EventNotificationRecord record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();
            String key = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8);
            chunks += ingest.ingest(bucket, key);
        }
        return "stored " + chunks + " chunks";
    }
}
