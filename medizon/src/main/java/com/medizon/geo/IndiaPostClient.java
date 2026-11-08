package com.medizon.geo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * India Post's PIN code directory through api.postalpincode.in (free, no key): the post offices, district and state
 * of a PIN code. Fills city and state when a PIN code is typed, and offers the post office areas as localities.
 */
@Component
public class IndiaPostClient {

    private final RestClient http;

    public IndiaPostClient(@Value("${medizon.location.indiapost.url:https://api.postalpincode.in}") String url) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    /**
     * @param city       the district, which is what Indian addresses use as the city
     * @param localities post office names in that PIN code ("Andheri East", "Marol Bazar" ...)
     */
    public record PincodeInfo(String pincode, String city, String district, String state, List<String> localities) {}

    /** The PIN code's details, or null when India Post doesn't know it. */
    public PincodeInfo lookup(String pincode) {
        JsonNode body;
        try {
            body = http.get().uri("/pincode/{pin}", pincode).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new GeoUnavailableException("India Post failed: " + e.getMessage(), e);
        }
        JsonNode r = body != null && body.isArray() && !body.isEmpty() ? body.get(0) : null;
        if (r == null || !"Success".equalsIgnoreCase(r.path("Status").asText())) return null;
        JsonNode offices = r.path("PostOffice");
        if (!offices.isArray() || offices.isEmpty()) return null;
        String district = clean(offices.get(0).path("District").asText(null));
        String state = clean(offices.get(0).path("State").asText(null));
        Set<String> names = new LinkedHashSet<>();
        offices.forEach(o -> { String n = clean(o.path("Name").asText(null)); if (n != null) names.add(n); });
        return new PincodeInfo(pincode, district, district, state, new ArrayList<>(names));
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() || t.equalsIgnoreCase("NA") ? null : t;
    }
}
