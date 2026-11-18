package com.vamsdest.rag;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.DeleteVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.PutInputVector;
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.VectorData;

/**
 * Amazon S3 Vectors index. Pay-per-use with no servers to run, which keeps the policy search cost low. The index is
 * created by the stack (AWS::S3Vectors::Index) with float32 vectors, cosine distance, and the embedding dimension.
 */
public final class S3VectorIndex implements VectorIndex {
    /** Keeps each request well inside the service limits. */
    static final int BATCH = 100;

    private final S3VectorsClient client;
    private final String bucket;
    private final String index;

    public S3VectorIndex(S3VectorsClient client, String bucket, String index) {
        this.client = client;
        this.bucket = bucket;
        this.index = index;
    }

    @Override
    public void put(List<Entry> entries) {
        for (int start = 0; start < entries.size(); start += BATCH) {
            List<PutInputVector> batch = entries.subList(start, Math.min(entries.size(), start + BATCH)).stream()
                    .map(entry -> PutInputVector.builder()
                            .key(entry.key())
                            .data(data(entry.vector()))
                            .build())
                    .toList();
            client.putVectors(PutVectorsRequest.builder()
                    .vectorBucketName(bucket)
                    .indexName(index)
                    .vectors(batch)
                    .build());
        }
    }

    @Override
    public void delete(List<String> keys) {
        for (int start = 0; start < keys.size(); start += BATCH) {
            client.deleteVectors(DeleteVectorsRequest.builder()
                    .vectorBucketName(bucket)
                    .indexName(index)
                    .keys(keys.subList(start, Math.min(keys.size(), start + BATCH)))
                    .build());
        }
    }

    @Override
    public List<Match> query(float[] vector, int topK) {
        List<Match> matches = new ArrayList<>();
        client.queryVectors(QueryVectorsRequest.builder()
                        .vectorBucketName(bucket)
                        .indexName(index)
                        .queryVector(data(vector))
                        .topK(topK)
                        .returnDistance(true)
                        .returnMetadata(false)
                        .build())
                .vectors()
                .forEach(match -> matches.add(new Match(match.key(),
                        match.distance() == null ? Double.MAX_VALUE : match.distance().doubleValue())));
        return matches;
    }

    private static VectorData data(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return VectorData.builder().float32(values).build();
    }
}
