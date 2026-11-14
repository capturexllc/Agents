package com.vamsdest.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds per-run objects that are not serializable (listeners, run contexts). Graph state carries only the string key,
 * because LangGraph4j copies state between steps and every value in it must serialize.
 */
final class RunRegistry {
    private static final Map<String, Object> OBJECTS = new ConcurrentHashMap<>();

    private RunRegistry() {
    }

    static String register(Object value) {
        String key = UUID.randomUUID().toString();
        OBJECTS.put(key, value);
        return key;
    }

    static <T> T get(String key, Class<T> type) {
        Object value = OBJECTS.get(key);
        if (!type.isInstance(value)) {
            throw new IllegalStateException("no " + type.getSimpleName() + " registered for key " + key);
        }
        return type.cast(value);
    }

    static void release(String key) {
        OBJECTS.remove(key);
    }
}
