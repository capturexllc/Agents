package com.vamsdest.agent;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Two breakers, one for model calls and one for web search. When a dependency fails most of the time, the breaker
 * opens and calls fail immediately with {@link CallNotPermittedException} instead of waiting on a timeout, so agents
 * can answer with the evidence they already have.
 */
public final class CircuitBreakers {
    private final CircuitBreaker model;
    private final CircuitBreaker search;

    public CircuitBreakers(RuntimeSettings settings) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .waitDurationInOpenState(Duration.ofSeconds(settings.openSeconds()))
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.model = registry.circuitBreaker("bedrock");
        this.search = registry.circuitBreaker("web-search");
    }

    public <T> T model(Supplier<T> call) {
        return model.executeSupplier(call);
    }

    public <T> T search(Supplier<T> call) {
        return search.executeSupplier(call);
    }

    public CircuitBreaker.State modelState() {
        return model.getState();
    }

    public CircuitBreaker.State searchState() {
        return search.getState();
    }
}
