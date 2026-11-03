package com.vamsdest.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vamsdest.api.TripRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoRunStoreTest {
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DynamoRunStore store = new DynamoRunStore(dynamo, "TripRuns", mapper, Duration.ofHours(1));

    @Test
    void appendUsesAtomicCounterForSequenceNumber() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder()
                .attributes(Map.of("eventCount", AttributeValue.fromN("7")))
                .build());

        TripEvent stored = store.append("run-1",
                TripEvent.status("run-1", "stay", "running", "Planning research."));

        assertEquals(7, stored.seq());
        ArgumentCaptor<UpdateItemRequest> update = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(update.capture());
        assertEquals("ADD eventCount :one", update.getValue().updateExpression());
        ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(put.capture());
        assertEquals("EVT#0000000007", put.getValue().item().get("sk").s());
    }

    @Test
    void createStartsPlanningWithZeroEvents() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        RunRecord record = store.create("run-2", "traveler-a", new TripRequest("Kyoto", null,
                LocalDate.parse("2030-05-01"), LocalDate.parse("2030-05-07"), null), now);

        assertEquals(RunRecord.PLANNING, record.status());
        ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(put.capture());
        assertEquals("0", put.getValue().item().get("eventCount").n());
        assertEquals(String.valueOf(now.plus(Duration.ofHours(1)).getEpochSecond()),
                put.getValue().item().get("expiresAt").n());
    }
}
