package com.vamsdest.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

class AgentHarnessTest {
    private static final SourceReference KYOTO = new SourceReference(
            "Kyoto events", "https://example.com/kyoto", "Aoi Matsuri is held on May 15.", Instant.EPOCH);

    @Test
    void executesModelRequestedSearchesAndReturnsSourcedAnswer() {
        FakeBedrock bedrock = new FakeBedrock(
                toolUse("t1", "Kyoto events May 2030"),
                endTurn("Aoi Matsuri runs May 15 (https://example.com/kyoto)."));
        List<String> searched = new ArrayList<>();
        AgentHarness harness = harness(bedrock, query -> {
            searched.add(query);
            return List.of(KYOTO);
        });
        List<String> progress = new ArrayList<>();

        AgentHarness.AgentOutcome outcome = harness.run("Role", "Task", 3, new AgentHarness.AgentListener() {
            @Override
            public void onSearch(String query) {
                progress.add("search:" + query);
            }

            @Override
            public void onThinking() {
                progress.add("thinking");
            }
        });

        assertEquals(List.of("Kyoto events May 2030"), searched);
        assertEquals(List.of("search:Kyoto events May 2030", "thinking"), progress);
        assertEquals(List.of(KYOTO), outcome.sources());
        assertEquals(1, outcome.searches());
        assertTrue(outcome.answer().contains("Aoi Matsuri"));
        ContentBlock toolResult = bedrock.requests.get(1).messages().get(2).content().get(0);
        assertEquals("t1", toolResult.toolResult().toolUseId());
        assertTrue(toolResult.toolResult().content().get(0).text().contains("https://example.com/kyoto"));
        assertTrue(bedrock.requests.get(0).system().get(0).text().contains("untrusted data"));
    }

    @Test
    void enforcesSearchBudget() {
        FakeBedrock bedrock = new FakeBedrock(toolUse("t1", "first"), toolUse("t2", "second"), endTurn("done"));
        List<String> searched = new ArrayList<>();
        AgentHarness harness = harness(bedrock, query -> {
            searched.add(query);
            return List.of(KYOTO);
        });

        AgentHarness.AgentOutcome outcome = harness.run("Role", "Task", 1, AgentHarness.AgentListener.NONE);

        assertEquals(List.of("first"), searched);
        assertEquals("done", outcome.answer());
        ContentBlock refused = bedrock.requests.get(2).messages().get(4).content().get(0);
        assertEquals(ToolResultStatus.ERROR, refused.toolResult().status());
    }

    @Test
    void reportsSearchFailureToModelInsteadOfAborting() {
        FakeBedrock bedrock = new FakeBedrock(toolUse("t1", "anything"), endTurn("partial answer"));
        AgentHarness harness = harness(bedrock, query -> {
            throw new IllegalStateException("provider down");
        });

        AgentHarness.AgentOutcome outcome = harness.run("Role", "Task", 2, AgentHarness.AgentListener.NONE);

        assertEquals("partial answer", outcome.answer());
        assertTrue(outcome.sources().isEmpty());
    }

    @Test
    void toolFreeRunSendsNoToolConfig() {
        FakeBedrock bedrock = new FakeBedrock(endTurn("summary"));
        AgentHarness harness = harness(bedrock, query -> List.of());

        assertEquals("summary", harness.run("Role", "Task", 0, AgentHarness.AgentListener.NONE).answer());
        assertNull(bedrock.requests.get(0).toolConfig());
    }

    @Test
    void stopsAfterMaxTurns() {
        FakeBedrock bedrock = new FakeBedrock(toolUse("t1", "a"), toolUse("t2", "b"));
        AgentHarness harness = new AgentHarness(bedrock, query -> List.of(), "test-model", "", "", 2, 500);

        assertThrows(IllegalStateException.class,
                () -> harness.run("Role", "Task", 5, AgentHarness.AgentListener.NONE));
    }

    private static AgentHarness harness(FakeBedrock bedrock, TravelResearchProvider provider) {
        return new AgentHarness(bedrock, provider, "test-model", "", "", 6, 500);
    }

    private static ConverseResponse toolUse(String id, String query) {
        ToolUseBlock block = ToolUseBlock.builder()
                .toolUseId(id)
                .name(AgentHarness.SEARCH_TOOL)
                .input(Document.fromMap(Map.of("query", Document.fromString(query))))
                .build();
        return response(StopReason.TOOL_USE, ContentBlock.fromToolUse(block));
    }

    private static ConverseResponse endTurn(String text) {
        return response(StopReason.END_TURN, ContentBlock.fromText(text));
    }

    private static ConverseResponse response(StopReason stopReason, ContentBlock content) {
        return ConverseResponse.builder()
                .stopReason(stopReason)
                .output(ConverseOutput.fromMessage(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(content)
                        .build()))
                .build();
    }

    private static final class FakeBedrock implements BedrockRuntimeClient {
        private final Deque<ConverseResponse> responses;
        private final List<ConverseRequest> requests = new ArrayList<>();

        private FakeBedrock(ConverseResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            requests.add(request);
            return responses.removeFirst();
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
        }
    }
}
