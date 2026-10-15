package com.medizon.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.medizon.ai.AssistantClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Is the assistant service (the Python app) answering? Shows as "assistant" in /actuator/health. The shop keeps
 * working without it, so it reports DEGRADED rather than DOWN, which ranks below UP (management.endpoint.health.
 * status.order) and so doesn't fail the app's overall health.
 */
@Component("assistant")
@RequiredArgsConstructor
public class AssistantHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED", "The assistant isn't answering; the shop works without it");

    private final AssistantClient assistant;

    @Override
    public Health health() {
        long start = System.nanoTime();
        try {
            JsonNode res = assistant.health();
            long ms = (System.nanoTime() - start) / 1_000_000;
            return (res != null && res.path("ok").asBoolean() ? Health.up() : Health.status(DEGRADED))
                    .withDetail("responseMs", ms).build();
        } catch (RuntimeException e) {
            return Health.status(DEGRADED).withDetail("error", e.getMessage()).build();
        }
    }
}
