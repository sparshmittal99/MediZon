package com.medizon.geo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * LocationIQ (locationiq.com): address autocomplete while typing and "what's here" for a spot on the map, limited to
 * India. Free plan: 5,000 requests a day, 2 a second. Set {@code LOCATIONIQ_KEY}; without it {@link GeoService} uses
 * OpenStreetMap's Nominatim. The key stays on the server: browsers only talk to MediZon.
 */
@Slf4j
@Component
public class LocationIqClient implements GeoProvider {

    private final String key;
    private final RestClient search;
    private final RestClient autocomplete;

    public LocationIqClient(@Value("${medizon.location.locationiq.key:}") String key,
                            @Value("${medizon.location.locationiq.url:https://us1.locationiq.com/v1}") String url,
                            @Value("${medizon.location.locationiq.autocomplete-url:https://api.locationiq.com/v1}") String autocompleteUrl) {
        this.key = key.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.search = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.autocomplete = RestClient.builder().baseUrl(autocompleteUrl).requestFactory(factory).build();
    }

    @Override public String name() { return "LocationIQ"; }

    @Override public boolean isConfigured() { return !key.isEmpty(); }

    /** Places matching what's typed so far: buildings, landmarks, streets and areas. */
    @Override
    public List<GeoPlace> suggest(String q, int limit) {
        JsonNode body = call(() -> autocomplete.get().uri(u -> u.path("/autocomplete")
                .queryParam("key", key).queryParam("q", q).queryParam("countrycodes", "in")
                // Also keep to India's map area, in case a result slips past the country filter
                .queryParam("viewbox", GeoPlace.INDIA_VIEWBOX).queryParam("bounded", 1)
                .queryParam("limit", limit).queryParam("dedupe", 1).queryParam("normalizecity", 1)
                .queryParam("accept-language", "en").build()).retrieve().body(JsonNode.class));
        List<GeoPlace> out = new ArrayList<>();
        if (body != null && body.isArray()) body.forEach(n -> {
            if (!GeoPlace.inIndia(n)) return;          // never show a place outside India
            GeoPlace p = GeoPlace.from(n);
            if (p != null) out.add(p);
        });
        return out;
    }

    /** What's at this spot (street, area, city, PIN code), or null when there's nothing on the map here. */
    @Override
    public GeoPlace reverse(double lat, double lon) {
        JsonNode body = call(() -> search.get().uri(u -> u.path("/reverse")
                .queryParam("key", key).queryParam("lat", lat).queryParam("lon", lon).queryParam("format", "json")
                .queryParam("addressdetails", 1).queryParam("normalizecity", 1).queryParam("accept-language", "en")
                .build()).retrieve().body(JsonNode.class));
        return GeoPlace.from(body);
    }

    /**
     * Lookups left today on the LocationIQ account, as LocationIQ counts them (survives our restarts and counts every
     * server using the key). Null when it can't be read. Checking the balance doesn't use any quota.
     */
    @Override
    public Integer remainingToday() {
        JsonNode body = call(() -> search.get().uri(u -> u.path("/balance").queryParam("key", key)
                .queryParam("format", "json").build()).retrieve().body(JsonNode.class));
        JsonNode day = body == null ? null : body.path("balance").path("day");
        return day != null && day.isNumber() ? day.asInt() : null;
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        if (!isConfigured()) throw new GeoUnavailableException("LocationIQ key not set");
        try {
            return request.get();
        } catch (HttpClientErrorException.NotFound e) {
            return null;            // "Unable to geocode": nothing here, not a failure
        } catch (HttpClientErrorException e) {
            // 401 bad key, 403 domain not allowed, 429 over the per-second or daily limit
            throw new GeoUnavailableException("LocationIQ answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new GeoUnavailableException("LocationIQ failed: " + e.getMessage(), e);
        }
    }
}
