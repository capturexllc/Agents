package com.vamsdest.agent;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockGuardrailConfiguration;
import dev.langchain4j.model.chat.ChatModel;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Builds the LangChain4j chat model for a run. The model id comes from the runtime settings, so a change in S3 takes
 * effect on the next run. The Bedrock guardrail, when configured, is applied to every call.
 */
public class ModelFactory {
    private final BedrockRuntimeClient client;
    private final String region;
    private final String guardrailId;
    private final String guardrailVersion;

    public ModelFactory(BedrockRuntimeClient client, String region, String guardrailId, String guardrailVersion) {
        if (!guardrailId.isBlank() && guardrailVersion.isBlank()) {
            throw new IllegalStateException("BEDROCK_GUARDRAIL_VERSION is required with BEDROCK_GUARDRAIL_ID");
        }
        this.client = client;
        this.region = region;
        this.guardrailId = guardrailId;
        this.guardrailVersion = guardrailVersion;
    }

    public ChatModel create(String modelId) {
        BedrockChatModel.Builder builder = BedrockChatModel.builder()
                .client(client)
                .region(Region.of(region))
                .modelId(modelId);
        if (!guardrailId.isBlank()) {
            builder.defaultRequestParameters(BedrockChatRequestParameters.builder()
                    .guardrailConfiguration(new BedrockGuardrailConfiguration(guardrailId, guardrailVersion,
                            BedrockGuardrailConfiguration.ProcessingMode.SYNC))
                    .build());
        }
        return builder.build();
    }
}
