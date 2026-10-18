package com.medizon.ai;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Calls Google Gemini (aistudio.google.com) for prescription reading and the chat fallback. Set {@code GEMINI_API_KEY};
 * {@code GEMINI_MODEL} changes the model. The free tier may use what's sent to improve Google's models, so use a paid
 * key with real customers' prescriptions.
 *
 * <p>Gemini being down must never slow MediZon down: after a failure it's skipped for a while (longer when the quota
 * ran out) and callers carry on without it, so prescriptions go straight to the pharmacist and chat stays with the
 * Python assistant.
 */
@Slf4j
@Component
public class GeminiClient {

    private static final Duration COOL_DOWN = Duration.ofMinutes(2);
    private static final Duration QUOTA_COOL_DOWN = Duration.ofMinutes(15);
    /** Calls at once: a slow Gemini can't tie up the web server's threads; the rest go on without it. */
    private static final int MAX_CONCURRENT = 4;

    private final String apiKey;
    private final String model;
    private final boolean chosen;
    private final RestClient http;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private volatile Instant skipUntil = Instant.EPOCH;

    public GeminiClient(@Value("${medizon.ai.provider:gemini}") String provider,
                        @Value("${medizon.ai.gemini.api-key:}") String apiKey,
                        @Value("${medizon.ai.gemini.model:gemini-flash-latest}") String model,
                        @Value("${medizon.ai.gemini.url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl) {
        this.chosen = "gemini".equalsIgnoreCase(provider.trim());
        this.apiKey = apiKey.trim();
        this.model = model.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(45_000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Chosen as the AI provider and has a key. */
    public boolean isConfigured() { return chosen && !apiKey.isEmpty(); }

    /** Configured and not resting after a failure. */
    public boolean isAvailable() { return isConfigured() && Instant.now().isAfter(skipUntil); }

    /**
     * Sends a {@code generateContent} request and returns the text of the answer.
     *
     * @throws GeminiUnavailableException when it's down, out of quota, resting after a failure, or answers nothing
     */
    public String generate(ObjectNode body) {
        if (!isAvailable()) throw new GeminiUnavailableException("Gemini is " + (isConfigured() ? "resting after a failure" : "not configured"));
        try {
            if (!slots.tryAcquire(2, TimeUnit.SECONDS)) throw new GeminiUnavailableException("Gemini is busy with other requests");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiUnavailableException("Interrupted waiting for Gemini");
        }
        try {
            JsonNode res = http.post().uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String text = res == null ? null : res.at("/candidates/0/content/parts/0/text").asText(null);
            if (text == null || text.isBlank()) throw new GeminiUnavailableException("Gemini returned no answer");
            return text;
        } catch (HttpClientErrorException.TooManyRequests e) {
            rest(QUOTA_COOL_DOWN, "quota used up");
            throw new GeminiUnavailableException("Gemini quota used up", e);
        } catch (RestClientException e) {
            rest(COOL_DOWN, e.getMessage());
            throw new GeminiUnavailableException("Gemini unreachable", e);
        } finally {
            slots.release();
        }
    }

    /** For the health check: why it's not available, or null when it is. */
    public String unavailableReason() {
        if (!isConfigured()) return "not configured (no GEMINI_API_KEY)";
        return isAvailable() ? null : "skipped after a failure until " + skipUntil;
    }

    private void rest(Duration d, String why) {
        skipUntil = Instant.now().plus(d);
        log.warn("Gemini failed ({}); skipping it for {} minutes", why, d.toMinutes());
    }

    public static class GeminiUnavailableException extends RuntimeException {
        public GeminiUnavailableException(String message) { super(message); }
        public GeminiUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
