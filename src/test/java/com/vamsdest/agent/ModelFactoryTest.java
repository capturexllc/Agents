package com.vamsdest.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.Map;
import software.amazon.awssdk.awscore.AwsResponseMetadata;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeResponseMetadata;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Runs a real LangChain4j Bedrock call with the Bedrock client mocked. This exercises the Converse request builders,
 * which is where a mismatched AWS SDK version fails at run time (NoSuchMethodError), not at build time.
 */
class ModelFactoryTest {

    @Test
    void chatModelBuildsConverseRequestsAndReadsTheReply() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        AwsResponseMetadata awsMetadata = new AwsResponseMetadata(Map.of("AWS_REQUEST_ID", "test-request")) {
        };
        BedrockRuntimeResponseMetadata metadata = BedrockRuntimeResponseMetadata.create(awsMetadata);
        ConverseResponse.Builder reply = ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText("Pack a light jacket."))
                        .build()))
                .stopReason(StopReason.END_TURN)
                .usage(TokenUsage.builder().inputTokens(10).outputTokens(5).totalTokens(15).build());
        reply.responseMetadata(metadata);
        when(client.converse(any(ConverseRequest.class))).thenReturn(reply.build());
        ChatModel model = new ModelFactory(client, "ca-central-1", "", "").create("ca.amazon.nova-lite-v1:0");

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from("What should I pack?"))
                .toolSpecifications(ToolSpecification.builder().name("web_search").description("search")
                        .build())
                .maxOutputTokens(100)
                .build());

        assertEquals("Pack a light jacket.", response.aiMessage().text());
    }
}
