package com.vamsdest.config;

import com.vamsdest.agent.ModelFactory;
import com.vamsdest.rag.LocalEmbedder;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import com.vamsdest.rag.ChunkStore;
import com.vamsdest.rag.Embedder;
import com.vamsdest.rag.PolicyIndex;
import com.vamsdest.rag.PolicyRetriever;
import java.time.Clock;
import com.vamsdest.agent.PromptCatalog;
import com.vamsdest.agent.RuntimeSettings;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;

/** Beans for the agent runtime: prompt catalog (S3 with bundled fallback), runtime settings, and model factory. */
@Configuration
public class AgentConfiguration {

    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${vamsdest.bedrock.region}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean
    PromptCatalog promptCatalog(S3Client s3,
            @Value("${vamsdest.prompts.bucket:}") String bucket,
            @Value("${vamsdest.prompts.prefix:}") String prefix,
            @Value("${vamsdest.prompts.cache-seconds:300}") long cacheSeconds) {
        return new PromptCatalog(s3, bucket, prefix, Duration.ofSeconds(cacheSeconds));
    }

    @Bean
    RuntimeSettings runtimeSettings(
            @Value("${vamsdest.bedrock.model-id}") String modelId,
            @Value("${vamsdest.agent.max-tokens:1500}") int maxTokens,
            @Value("${vamsdest.agent.max-turns:8}") int maxTurns,
            @Value("${vamsdest.agent.max-searches:4}") int specialistSearches,
            @Value("${vamsdest.agent.chat-max-searches:2}") int chatSearches) {
        return new RuntimeSettings(modelId, maxTokens, maxTurns, specialistSearches, chatSearches,
                50, 10, 5, 30);
    }

    /** Policy corpus for the search_policies tool. Local runs use an empty corpus; the worker uses DynamoDB. */
    @Bean
    Embedder embedder() {
        // Local runs use the model bundled in the dependency jar.
        return new LocalEmbedder(() -> new AllMiniLmL6V2EmbeddingModel());
    }

    @Bean
    PolicyRetriever policyRetriever(Embedder embedder) {
        return new PolicyRetriever(embedder,
                new PolicyIndex(ChunkStore.EMPTY::loadAll, Duration.ofMinutes(5), Clock.systemUTC()));
    }

    @Bean
    ModelFactory modelFactory(BedrockRuntimeClient client,
            @Value("${vamsdest.bedrock.region}") String region,
            @Value("${vamsdest.bedrock.guardrail-id:}") String guardrailId,
            @Value("${vamsdest.bedrock.guardrail-version:}") String guardrailVersion) {
        return new ModelFactory(client, region, guardrailId, guardrailVersion);
    }
}
