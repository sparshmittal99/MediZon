package com.medizon.geo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OpenStreetMap's public Nominatim geocoder: the last fallback, when Ola Maps and LocationIQ have no key, are down or used up their daily
 * quotas. Its usage policy allows one request a second with an identifying User-Agent, so calls are spaced out here
 * and give up when the queue gets long.
 */
@Component
public class NominatimClient implements GeoProvider {

    private static final long GAP_MS = 1_100;

    private final RestClient http;
    private final ReentrantLock lock = new ReentrantLock(true);
    private long lastCall;

    public NominatimClient(@Value("${medizon.location.nominatim.url:https://nominatim.openstreetmap.org}") String url,
                           @Value("${medizon.location.nominatim.user-agent:MediZon/1.0 (online pharmacy; address lookup)}") String userAgent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(6_000);
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader("User-Agent", userAgent).build();
    }

    @Override public String name() { return "OpenStreetMap"; }

    /** Public service, no key. */
    @Override public boolean isConfigured() { return true; }

    @Override
    public List<GeoPlace> suggest(String q, int limit) {
        JsonNode body = call(() -> http.get().uri(u -> u.path("/search").queryParam("format", "jsonv2")
                .queryParam("addressdetails", 1).queryParam("countrycodes", "in").queryParam("limit", limit)
                .queryParam("viewbox", GeoPlace.INDIA_VIEWBOX).queryParam("bounded", 1)
                .queryParam("accept-language", "en").queryParam("q", q).build()).retrieve().body(JsonNode.class));
        List<GeoPlace> out = new ArrayList<>();
        if (body != null && body.isArray()) body.forEach(n -> {
            if (!GeoPlace.inIndia(n)) return;
            GeoPlace p = GeoPlace.from(n);
            if (p != null) out.add(p);
        });
        return out;
    }

    @Override
    public GeoPlace reverse(double lat, double lon) {
        JsonNode body = call(() -> http.get().uri(u -> u.path("/reverse").queryParam("format", "jsonv2")
                .queryParam("addressdetails", 1).queryParam("accept-language", "en")
                .queryParam("lat", lat).queryParam("lon", lon).build()).retrieve().body(JsonNode.class));
        return body == null || body.has("error") ? null : GeoPlace.from(body);
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            if (!lock.tryLock(3, TimeUnit.SECONDS)) throw new GeoUnavailableException("Nominatim busy");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeoUnavailableException("Interrupted");
        }
        try {
            long wait = lastCall + GAP_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastCall = System.currentTimeMillis();
            return request.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeoUnavailableException("Interrupted");
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw new GeoUnavailableException("Nominatim failed: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }
}
