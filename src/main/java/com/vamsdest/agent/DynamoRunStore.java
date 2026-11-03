package com.vamsdest.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vamsdest.api.TripRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * DynamoDB-backed run store for the AWS deployment. Single table, keys:
 * <ul>
 *   <li>{@code pk = RUN#<runId>}, {@code sk = META}: owner, request, status, and {@code eventCount}</li>
 *   <li>{@code pk = RUN#<runId>}, {@code sk = EVT#<10-digit seq>}: one event as JSON</li>
 * </ul>
 * Sequence numbers come from an atomic counter on META, so concurrent agents never reuse one. The
 * {@code expiresAt} attribute drives DynamoDB TTL, which implements the retention window.
 */
public final class DynamoRunStore implements RunStore {
    private static final String META = "META";
    private static final String EVENT_PREFIX = "EVT#";
    private static final int SEQ_WIDTH = 10;

    private final DynamoDbClient dynamo;
    private final String table;
    private final ObjectMapper mapper;
    private final Duration retention;

    public DynamoRunStore(DynamoDbClient dynamo, String table, ObjectMapper mapper, Duration retention) {
        this.dynamo = dynamo;
        this.table = table;
        this.mapper = mapper;
        this.retention = retention;
    }

    @Override
    public RunRecord create(String runId, String ownerId, TripRequest request, Instant createdAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(pk(runId)));
        item.put("sk", s(META));
        item.put("ownerId", s(ownerId));
        item.put("request", s(json(request)));
        item.put("createdAt", s(createdAt.toString()));
        item.put("status", s(RunRecord.PLANNING));
        item.put("eventCount", n(0));
        item.put("expiresAt", n(expiresAt(createdAt)));
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk)")
                    .build());
        } catch (ConditionalCheckFailedException exception) {
            throw new IllegalStateException("Trip run " + runId + " already exists", exception);
        }
        return new RunRecord(runId, ownerId, request, createdAt, RunRecord.PLANNING);
    }

    @Override
    public Optional<RunRecord> findOwned(String runId, String ownerId) {
        return find(runId).filter(record -> record.ownerId().equals(ownerId));
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(metaKey(runId))
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RunRecord(
                runId,
                item.get("ownerId").s(),
                read(item.get("request").s(), TripRequest.class),
                Instant.parse(item.get("createdAt").s()),
                item.get("status").s()));
    }

    @Override
    public TripEvent append(String runId, TripEvent event) {
        long seq;
        try {
            Map<String, AttributeValue> counter = dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(metaKey(runId))
                    .updateExpression("ADD eventCount :one")
                    .conditionExpression("attribute_exists(pk)")
                    .expressionAttributeValues(Map.of(":one", n(1)))
                    .returnValues(ReturnValue.UPDATED_NEW)
                    .build()).attributes();
            seq = Long.parseLong(counter.get("eventCount").n());
        } catch (ConditionalCheckFailedException exception) {
            throw new IllegalStateException("Unknown trip run " + runId, exception);
        }
        TripEvent stored = event.withSeq(seq);
        dynamo.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(Map.of(
                        "pk", s(pk(runId)),
                        "sk", s(eventKey(seq)),
                        "data", s(json(stored)),
                        "expiresAt", n(expiresAt(Instant.now()))))
                .build());
        return stored;
    }

    @Override
    public List<TripEvent> eventsAfter(String runId, long afterSeq) {
        List<TripEvent> events = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            var page = dynamo.query(QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("pk = :pk AND sk BETWEEN :from AND :to")
                    .expressionAttributeValues(Map.of(
                            ":pk", s(pk(runId)),
                            ":from", s(eventKey(Math.max(afterSeq, 0) + 1)),
                            ":to", s(EVENT_PREFIX + "9".repeat(SEQ_WIDTH))))
                    .consistentRead(true)
                    .exclusiveStartKey(startKey)
                    .build());
            for (Map<String, AttributeValue> item : page.items()) {
                events.add(read(item.get("data").s(), TripEvent.class));
            }
            startKey = page.hasLastEvaluatedKey() ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return events;
    }

    @Override
    public void setStatus(String runId, String status) {
        dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(table)
                .key(metaKey(runId))
                .updateExpression("SET #status = :status")
                .conditionExpression("attribute_exists(pk)")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":status", s(status)))
                .build());
    }

    private long expiresAt(Instant from) {
        return from.plus(retention).getEpochSecond();
    }

    private Map<String, AttributeValue> metaKey(String runId) {
        return Map.of("pk", s(pk(runId)), "sk", s(META));
    }

    private static String pk(String runId) {
        return "RUN#" + runId;
    }

    private static String eventKey(long seq) {
        return EVENT_PREFIX + String.format("%0" + SEQ_WIDTH + "d", seq);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize run data", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read stored run data", exception);
        }
    }
}
