package com.medizon.monitoring;

import com.medizon.ai.GroqClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Groq (the chat fallback), as "groq" in /actuator/health. Optional, so never DOWN: DEGRADED when it's failing or out
 * of quota. Makes no call to Groq, so checking health costs no quota.
 */
@Component("groq")
@RequiredArgsConstructor
public class GroqHealthIndicator implements HealthIndicator {

    private final GroqClient groq;

    @Override
    public Health health() {
        if (!groq.isConfigured()) return Health.unknown().withDetail("reason", groq.unavailableReason()).build();
        String why = groq.unavailableReason();
        if (why == null) return Health.up().build();
        return Health.status(AssistantHealthIndicator.DEGRADED).withDetail("reason", why)
                .withDetail("effect", "chat falls back to Gemini, then the Python assistant only").build();
    }
}
