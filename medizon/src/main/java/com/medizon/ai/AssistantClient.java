package com.medizon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

/**
 * HTTP client for the MediZon Assistant service (the Python app in /medizon-assistant). When it can't be reached,
 * chat skips it for a short while, so a hung or stopped assistant doesn't make every message wait for a timeout.
 */
@Component
public class AssistantClient {

    static final Duration COOL_DOWN = Duration.ofSeconds(30);

    private final RestClient http;
    private volatile Instant skipUntil = Instant.EPOCH;
    /** For health checks and monitoring: gives up quickly so a hung assistant can't stall the health endpoint. */
    private final RestClient quick;

    public AssistantClient(@Value("${medizon.assistant.url:http://localhost:8001}") String baseUrl,
                           @Value("${medizon.assistant.token:}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(15_000);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (!token.isBlank()) builder.defaultHeader("X-Assistant-Token", token);
        this.http = builder.build();

        SimpleClientHttpRequestFactory fast = new SimpleClientHttpRequestFactory();
        fast.setConnectTimeout(1_500);
        fast.setReadTimeout(2_500);
        this.quick = builder.requestFactory(fast).build();
    }

    /** The assistant's own health check. */
    public JsonNode health() {
        return quick.get().uri("/health").retrieve().body(JsonNode.class);
    }

    /** Request timings and recent errors inside the assistant service. */
    public JsonNode metrics() {
        return quick.get().uri("/metrics").retrieve().body(JsonNode.class);
    }

    /** Not known to be down right now. */
    public boolean isAvailable() { return Instant.now().isAfter(skipUntil); }

    /** @throws ResourceAccessException when it's unreachable, or was a moment ago */
    public JsonNode chat(ObjectNode request) {
        if (!isAvailable()) throw new ResourceAccessException("Assistant service unreachable a moment ago; skipping it");
        try {
            return http.post()
                    .uri("/v1/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {      // refused, timed out, DNS...: not a bad answer, an absent service
            skipUntil = Instant.now().plus(COOL_DOWN);
            throw e;
        }
    }
}
