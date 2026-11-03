package com.vamsdest.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class BedrockConfiguration {
    @Bean(destroyMethod = "close")
    BedrockRuntimeClient bedrockRuntimeClient(@Value("${vamsdest.bedrock.region}") String region) {
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(90))
                        .build())
                .build();
    }
}
