package com.medizon.monitoring;

import com.medizon.ai.GeminiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Gemini (prescription reading and the chat fallback), as "gemini" in /actuator/health. Like the assistant it's
 * optional, so it's never DOWN: DEGRADED when it's failing or out of quota. Makes no call to Google, so checking
 * health costs no quota.
 */
@Component("gemini")
@RequiredArgsConstructor
public class GeminiHealthIndicator implements HealthIndicator {

    private final GeminiClient gemini;

    @Override
    public Health health() {
        if (!gemini.isConfigured()) return Health.unknown().withDetail("reason", gemini.unavailableReason()).build();
        String why = gemini.unavailableReason();
        if (why == null) return Health.up().build();
        return Health.status(AssistantHealthIndicator.DEGRADED).withDetail("reason", why)
                .withDetail("effect", "prescriptions go straight to the pharmacist; chat uses the Python assistant only").build();
    }
}
