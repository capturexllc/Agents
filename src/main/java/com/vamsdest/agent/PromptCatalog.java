package com.vamsdest.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Loads prompt templates and runtime settings. The S3 copy wins when it exists; otherwise the copy bundled in the
 * jar is used. Reads are cached for {@code ttl}, so an edit in S3 takes effect within that window without a deploy.
 *
 * <p>Layout in the bucket: {@code <prefix>prompts/<name>.md} and {@code <prefix>config/runtime.yml}.
 */
public final class PromptCatalog {
    static final String RUNTIME_CONFIG = "runtime.yml";
    private static final Logger log = LoggerFactory.getLogger(PromptCatalog.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** @param s3 client, or null to read only the bundled copies (local development and tests) */
    public PromptCatalog(S3Client s3, String bucket, String prefix, Duration ttl, Clock clock) {
        this.s3 = s3;
        this.bucket = bucket == null ? "" : bucket.strip();
        this.prefix = normalizePrefix(prefix);
        this.ttl = ttl;
        this.clock = clock;
    }

    public PromptCatalog(S3Client s3, String bucket, String prefix, Duration ttl) {
        this(s3, bucket, prefix, ttl, Clock.systemUTC());
    }

    /** Returns the prompt template {@code prompts/<name>.md} without substitution. */
    public String template(String name) {
        return load("prompts/" + name + ".md", "prompts/" + name + ".md");
    }

    /** Returns the prompt template with {@code {{placeholders}}} filled from {@code values}. */
    public String prompt(String name, Map<String, String> values) {
        return render(template(name), values);
    }

    /** Returns the parsed runtime document (empty when it cannot be read or parsed). */
    public Map<String, Object> runtimeConfig() {
        String text = load("config/" + RUNTIME_CONFIG, "prompts/" + RUNTIME_CONFIG);
        Object parsed = new Yaml().load(text);
        if (parsed instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) map;
            return config;
        }
        return Map.of();
    }

    /**
     * Replaces every {@code {{name}}} with its value. A placeholder with no value is an error, so a typo in a
     * template fails loudly instead of sending the model a literal "{{name}}".
     */
    public static String render(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Prompt placeholder has no value: " + key);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String load(String key, String bundledResource) {
        Cached cached = cache.get(key);
        Instant now = clock.instant();
        if (cached != null && cached.loadedAt().plus(ttl).isAfter(now)) {
            return cached.text();
        }
        String text = fromS3(prefix + key);
        if (text == null) {
            text = bundled(bundledResource);
        }
        cache.put(key, new Cached(text, now));
        return text;
    }

    private String fromS3(String key) {
        if (s3 == null || bucket.isEmpty()) {
            return null;
        }
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return object.asString(StandardCharsets.UTF_8);
        } catch (NoSuchKeyException missing) {
            return null;
        } catch (RuntimeException exception) {
            log.warn("could not read s3://{}/{}, using the bundled copy: {}", bucket, key, exception.getMessage());
            return null;
        }
    }

    private static String bundled(String resource) {
        try (InputStream in = PromptCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled prompt resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled prompt resource " + resource, exception);
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String value = prefix.strip();
        return value.endsWith("/") ? value : value + "/";
    }

    private record Cached(String text, Instant loadedAt) {
    }
}
