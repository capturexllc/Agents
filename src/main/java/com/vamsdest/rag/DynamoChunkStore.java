package com.vamsdest.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * DynamoDB chunk store. Keys: {@code pk = DOC#<docId>}, {@code sk = CHUNK#<index, zero padded>}. The vector is stored as
 * little-endian float32 bytes.
 */
public final class DynamoChunkStore implements ChunkStore {
    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoChunkStore(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public void replaceDocument(String docId, List<StoredChunk> chunks) {
        String pk = "DOC#" + docId;
        dynamo.queryPaginator(QueryRequest.builder()
                        .tableName(table)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", s(pk)))
                        .projectionExpression("pk, sk")
                        .build())
                .items()
                .forEach(item -> dynamo.deleteItem(DeleteItemRequest.builder()
                        .tableName(table)
                        .key(Map.of("pk", item.get("pk"), "sk", item.get("sk")))
                        .build()));
        for (StoredChunk chunk : chunks) {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(Map.of(
                            "pk", s(pk),
                            "sk", s(String.format("CHUNK#%06d", chunk.index())),
                            "docId", s(docId),
                            "index", AttributeValue.builder().n(String.valueOf(chunk.index())).build(),
                            "source", s(chunk.source()),
                            "location", s(chunk.location()),
                            "text", s(chunk.text()),
                            "vector", AttributeValue.builder().b(SdkBytes.fromByteArray(toBytes(chunk.vector()))).build()))
                    .build());
        }
    }

    @Override
    public List<StoredChunk> loadAll() {
        List<StoredChunk> chunks = new ArrayList<>();
        dynamo.scanPaginator(software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder()
                        .tableName(table).build())
                .items()
                .forEach(item -> {
                    if (item.containsKey("vector")) {
                        chunks.add(new StoredChunk(
                                item.get("docId").s(),
                                Integer.parseInt(item.get("index").n()),
                                item.get("source").s(),
                                item.get("location").s(),
                                item.get("text").s(),
                                fromBytes(item.get("vector").b().asByteArray())));
                    }
                });
        return chunks;
    }

    static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    static float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }
        return vector;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
