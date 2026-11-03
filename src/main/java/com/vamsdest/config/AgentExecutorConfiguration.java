package com.vamsdest.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AgentExecutorConfiguration {
    @Bean(name = "agentExecutor")
    Executor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(9);
        executor.setQueueCapacity(60);
        executor.setThreadNamePrefix("vamsdest-agent-");
        executor.initialize();
        return executor;
    }
}