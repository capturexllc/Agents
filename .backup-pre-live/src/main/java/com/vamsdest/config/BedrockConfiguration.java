package com.vamsdest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class BedrockConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "vamsdest.bedrock.enabled", havingValue = "true")
    BedrockRuntimeClient bedrockRuntimeClient(@Value("${vamsdest.bedrock.region:us-east-1}") String region) {
        return BedrockRuntimeClient.builder().region(Region.of(region)).build();
    }
}