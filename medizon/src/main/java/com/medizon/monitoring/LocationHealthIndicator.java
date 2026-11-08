package com.medizon.monitoring;

import com.medizon.geo.GeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Address lookups (Ola Maps, then LocationIQ, then OpenStreetMap; India Post for PIN codes), as "location" in
 * /actuator/health. Never DOWN: the forms still work by hand. DEGRADED while the preferred geocoder or India Post is
 * off. Makes no outside call.
 */
@Component("location")
@RequiredArgsConstructor
public class LocationHealthIndicator implements HealthIndicator {

    private final GeoService geo;

    @Override
    public Health health() {
        if (!geo.anyKeyedProvider()) {
            return Health.unknown().withDetail("reason", "No Ola Maps or LocationIQ key set; OpenStreetMap answers address lookups").build();
        }
        String off = geo.primaryOffReason();
        if (off != null) {
            return Health.status(AssistantHealthIndicator.DEGRADED).withDetail("reason", off)
                    .withDetail("effect", geo.provider() + " answers address lookups").build();
        }
        if (geo.isIndiaPostResting()) {
            return Health.status(AssistantHealthIndicator.DEGRADED).withDetail("reason", "India Post isn't answering")
                    .withDetail("effect", "customers type city and state themselves; the delivery check still works").build();
        }
        return Health.up().withDetail("reason", geo.provider() + " answers address lookups").build();
    }
}
