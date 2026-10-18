package com.medizon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Calls Groq (console.groq.com) for the chat fallback: fast, with a large free daily quota. Set {@code GROQ_API_KEY};
 * {@code GROQ_MODEL} changes the model. Text only: prescriptions are still read by Gemini.
 *
 * <p>Like {@link GeminiClient}, a failing Groq is skipped for a while (longer when the quota ran out) and chat carries
 * on with Gemini, then the Python reply.
 */
@Slf4j
@Component
public class GroqClient {

    private static final Duration COOL_DOWN = Duration.ofMinutes(2);
    private static final Duration QUOTA_COOL_DOWN = Duration.ofMinutes(5);
    private static final int MAX_CONCURRENT = 4;

    private final String apiKey;
    private final String model;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private volatile Instant skipUntil = Instant.EPOCH;

    public GroqClient(@Value("${medizon.ai.groq.api-key:}") String apiKey,
                      @Value("${medizon.ai.groq.model:llama-3.3-70b-versatile}") String model,
                      @Value("${medizon.ai.groq.url:https://api.groq.com/openai/v1}") String baseUrl) {
        this.apiKey = apiKey.trim();
        this.model = model.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Has a key. */
    public boolean isConfigured() { return !apiKey.isEmpty(); }

    /** Configured and not resting after a failure. */
    public boolean isAvailable() { return isConfigured() && Instant.now().isAfter(skipUntil); }

    /**
     * Sends one system instruction and one user message and returns the answer's text.
     *
     * @throws GroqUnavailableException when it's down, out of quota, resting after a failure, or answers nothing
     */
    public String chat(String system, String message, double temperature) {
        if (!isAvailable()) throw new GroqUnavailableException("Groq is " + (isConfigured() ? "resting after a failure" : "not configured"));
        try {
            if (!slots.tryAcquire(2, TimeUnit.SECONDS)) throw new GroqUnavailableException("Groq is busy with other requests");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GroqUnavailableException("Interrupted waiting for Groq");
        }
        try {
            ObjectNode body = json.createObjectNode().put("model", model).put("temperature", temperature);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", message);
            JsonNode res = http.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String text = res == null ? null : res.at("/choices/0/message/content").asText(null);
            if (text == null || text.isBlank()) throw new GroqUnavailableException("Groq returned no answer");
            return text;
        } catch (HttpClientErrorException.TooManyRequests e) {
            rest(QUOTA_COOL_DOWN, "quota used up");
            throw new GroqUnavailableException("Groq quota used up", e);
        } catch (RestClientException e) {
            rest(COOL_DOWN, e.getMessage());
            throw new GroqUnavailableException("Groq unreachable", e);
        } finally {
            slots.release();
        }
    }

    /** For the health check: why it's not available, or null when it is. */
    public String unavailableReason() {
        if (!isConfigured()) return "not configured (no GROQ_API_KEY)";
        return isAvailable() ? null : "skipped after a failure until " + skipUntil;
    }

    private void rest(Duration d, String why) {
        skipUntil = Instant.now().plus(d);
        log.warn("Groq failed ({}); skipping it for {} minutes", why, d.toMinutes());
    }

    public static class GroqUnavailableException extends RuntimeException {
        public GroqUnavailableException(String message) { super(message); }
        public GroqUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
