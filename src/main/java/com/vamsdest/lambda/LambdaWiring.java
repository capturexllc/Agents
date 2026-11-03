package com.vamsdest.lambda;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vamsdest.agent.AgentHarness;
import com.vamsdest.agent.DynamoRunStore;
import com.vamsdest.agent.JobDispatcher;
import com.vamsdest.agent.JobProcessor;
import com.vamsdest.agent.RunStore;
import com.vamsdest.agent.TavilyResearchProvider;
import com.vamsdest.agent.TravelResearchProvider;
import com.vamsdest.agent.TripCoordinator;
import com.vamsdest.service.TripService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Builds the object graph for the Lambda handlers. Spring is not used here, so cold start stays small and each
 * function creates only what it needs. Configuration comes from environment variables set by the SAM template.
 */
final class LambdaWiring {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static DynamoDbClient dynamo;
    private static RunStore store;

    private LambdaWiring() {
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    static Validator validator() {
        return VALIDATOR;
    }

    static synchronized RunStore store() {
        if (store == null) {
            if (dynamo == null) {
                dynamo = DynamoDbClient.create();
            }
            store = new DynamoRunStore(dynamo, env("TABLE_NAME"), MAPPER,
                    Duration.ofHours(longEnv("RUN_TTL_HOURS", 1)));
        }
        return store;
    }

    static TripService tripService() {
        JobDispatcher dispatcher = new SqsJobDispatcher(SqsClient.create(), env("JOB_QUEUE_URL"), MAPPER);
        return new TripService(store(), dispatcher, VALIDATOR);
    }

    static JobProcessor jobProcessor(ExecutorService specialistPool) {
        AgentHarness harness = new AgentHarness(
                BedrockRuntimeClient.create(),
                tavily(),
                env("BEDROCK_MODEL_ID"),
                envOrEmpty("BEDROCK_GUARDRAIL_ID"),
                envOrEmpty("BEDROCK_GUARDRAIL_VERSION"),
                (int) longEnv("AGENT_MAX_TURNS", 8),
                (int) longEnv("AGENT_MAX_TOKENS", 1500));
        TripCoordinator coordinator = new TripCoordinator(
                harness,
                store(),
                specialistPool,
                (int) longEnv("AGENT_MAX_SEARCHES", 4),
                (int) longEnv("AGENT_CHAT_MAX_SEARCHES", 2));
        return new JobProcessor(store(), coordinator);
    }

    static ExecutorService specialistPool() {
        // Four specialists run concurrently; the worker thread waits for them.
        return Executors.newFixedThreadPool(4);
    }

    private static TravelResearchProvider tavily() {
        String secretArn = env("TAVILY_SECRET_ARN");
        String apiKey = SecretsManagerClient.create()
                .getSecretValue(GetSecretValueRequest.builder().secretId(secretArn).build())
                .secretString();
        return new TavilyResearchProvider(apiKey, MAPPER);
    }

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value;
    }

    static String envOrEmpty(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    static long longEnv(String name, long fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.strip());
    }
}
