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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Calls Groq (console.groq.com) for the chat fallback: fast, with a free daily quota. Set {@code GROQ_API_KEY};
 * {@code GROQ_MODEL} is a comma-separated list of models tried in order. Groq's quota is per model, so when one runs
 * out the next one answers. Text only: prescriptions are still read by Gemini.
 *
 * <p>Like {@link GeminiClient}, a failing Groq is skipped for a while (a model whose quota ran out, just that model)
 * and chat carries on with Gemini, then the Python reply.
 */
@Slf4j
@Component
public class GroqClient {

    private static final Duration COOL_DOWN = Duration.ofMinutes(2);
    private static final Duration QUOTA_COOL_DOWN = Duration.ofMinutes(5);
    private static final int MAX_CONCURRENT = 4;

    private final String apiKey;
    /** Most replies are a few sentences; the cap also bounds reasoning, so a chat can't eat the per-minute tokens. */
    private static final int MAX_TOKENS = 1024;

    private final List<String> models;
    private final Map<String, Instant> modelSkipUntil = new ConcurrentHashMap<>();
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private volatile Instant skipUntil = Instant.EPOCH;

    public GroqClient(@Value("${medizon.ai.groq.api-key:}") String apiKey,
                      @Value("${medizon.ai.groq.model:openai/gpt-oss-120b,openai/gpt-oss-20b}") String models,
                      @Value("${medizon.ai.groq.url:https://api.groq.com/openai/v1}") String baseUrl) {
        this.apiKey = apiKey.trim();
        this.models = Arrays.stream(models.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Has a key. */
    public boolean isConfigured() { return !apiKey.isEmpty() && !models.isEmpty(); }

    /** Configured, not resting after a failure, and some model still has quota. */
    public boolean isAvailable() { return isConfigured() && Instant.now().isAfter(skipUntil) && nextModel() != null; }

    /**
     * Sends one system instruction and one user message and returns the answer's text.
     *
     * @throws GroqUnavailableException when it's down, out of quota, resting after a failure, or answers nothing
     */
    public String chat(String system, String message, double temperature) {
        ArrayNode messages = json.createArrayNode();
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", message);
        String text = complete(messages, null, temperature).path("content").asText(null);
        if (text == null || text.isBlank()) throw new GroqUnavailableException("Groq returned no answer");
        return text;
    }

    /**
     * Sends a conversation, optionally with tools the model may call, and returns the model's message: its
     * {@code content}, or the {@code tool_calls} it wants run first.
     *
     * @throws GroqUnavailableException when it's down, out of quota, resting after a failure, or answers nothing
     */
    public JsonNode complete(ArrayNode messages, ArrayNode tools, double temperature) {
        if (!isAvailable()) throw new GroqUnavailableException("Groq is " + (isConfigured() ? "resting after a failure" : "not configured"));
        try {
            if (!slots.tryAcquire(2, TimeUnit.SECONDS)) throw new GroqUnavailableException("Groq is busy with other requests");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GroqUnavailableException("Interrupted waiting for Groq");
        }
        try {
            for (String model = nextModel(); model != null; model = nextModel()) {
                try {
                    return ask(model, messages, tools, temperature);
                } catch (HttpClientErrorException.TooManyRequests e) {
                    modelSkipUntil.put(model, Instant.now().plus(QUOTA_COOL_DOWN));
                    log.warn("Groq {} quota used up; trying the next model for {} minutes", model, QUOTA_COOL_DOWN.toMinutes());
                }
            }
            throw new GroqUnavailableException("Groq quota used up on every model");
        } catch (HttpClientErrorException.BadRequest e) {
            // This request (often a garbled tool call), not Groq: don't rest, the next message may well work
            throw new GroqUnavailableException("Groq rejected the request: " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            rest(COOL_DOWN, e.getMessage());
            throw new GroqUnavailableException("Groq unreachable", e);
        } finally {
            slots.release();
        }
    }

    private JsonNode ask(String model, ArrayNode messages, ArrayNode tools, double temperature) {
        ObjectNode body = json.createObjectNode().put("model", model).put("temperature", temperature)
                .put("max_completion_tokens", MAX_TOKENS);
        // Reasoning models think before answering; a little is plenty for chat and saves time and quota
        if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low");
        body.set("messages", messages);
        if (tools != null && !tools.isEmpty()) body.set("tools", tools);
        JsonNode res = http.post().uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        JsonNode message = res == null ? null : res.at("/choices/0/message");
        if (message == null || !message.isObject()) throw new GroqUnavailableException("Groq returned no answer");
        return message;
    }

    /** The first model whose quota isn't used up, or null. */
    private String nextModel() {
        Instant now = Instant.now();
        return models.stream().filter(m -> now.isAfter(modelSkipUntil.getOrDefault(m, Instant.EPOCH))).findFirst().orElse(null);
    }

    /** For the health check: why it's not available, or null when it is. */
    public String unavailableReason() {
        if (!isConfigured()) return "not configured (no GROQ_API_KEY)";
        if (!Instant.now().isAfter(skipUntil)) return "skipped after a failure until " + skipUntil;
        return nextModel() == null ? "quota used up on every model; retrying soon" : null;
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
