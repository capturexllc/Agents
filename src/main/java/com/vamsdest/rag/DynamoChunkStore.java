package com.vamsdest.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Chunk text in DynamoDB. Keys: {@code pk = DOC#<docId>}, {@code sk = CHUNK#<index, zero padded>}. Vectors are not stored
 * here; they live in the S3 Vectors index under the same {@code docId#index} key.
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
        for (String key : keysOf(docId)) {
            StoredChunkKey parsed = StoredChunkKey.parse(key);
            dynamo.deleteItem(DeleteItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("pk", s(pk(docId)), "sk", s(sk(parsed.index()))))
                    .build());
        }
        for (StoredChunk chunk : chunks) {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(Map.of(
                            "pk", s(pk(docId)),
                            "sk", s(sk(chunk.index())),
                            "docId", s(docId),
                            "index", AttributeValue.builder().n(String.valueOf(chunk.index())).build(),
                            "source", s(chunk.source()),
                            "location", s(chunk.location()),
                            "text", s(chunk.text())))
                    .build());
        }
    }

    @Override
    public List<String> keysOf(String docId) {
        List<String> keys = new ArrayList<>();
        dynamo.queryPaginator(QueryRequest.builder()
                        .tableName(table)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", s(pk(docId))))
                        .projectionExpression("#i")
                        .expressionAttributeNames(Map.of("#i", "index"))
                        .build())
                .items()
                .forEach(item -> keys.add(ChunkStore.key(docId, Integer.parseInt(item.get("index").n()))));
        return keys;
    }

    @Override
    public List<StoredChunk> getAll(List<String> keys) {
        List<StoredChunk> chunks = new ArrayList<>();
        for (String key : keys) {
            StoredChunkKey parsed = StoredChunkKey.parse(key);
            Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("pk", s(pk(parsed.docId())), "sk", s(sk(parsed.index()))))
                    .consistentRead(true)
                    .build()).item();
            if (item != null && !item.isEmpty()) {
                chunks.add(new StoredChunk(parsed.docId(), parsed.index(),
                        item.get("source").s(), item.get("location").s(), item.get("text").s()));
            }
        }
        return chunks;
    }

    private static String pk(String docId) {
        return "DOC#" + docId;
    }

    private static String sk(int index) {
        return String.format("CHUNK#%06d", index);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    /** A parsed chunk key: {@code docId#index}. */
    private record StoredChunkKey(String docId, int index) {
        static StoredChunkKey parse(String key) {
            int split = key.lastIndexOf('#');
            return new StoredChunkKey(key.substring(0, split), Integer.parseInt(key.substring(split + 1)));
        }
    }
}
