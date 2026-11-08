package com.medizon.geo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ola Maps (Krutrim, maps.olakrutrim.com): India-first address suggestions (societies, wings, shops, landmarks) and
 * what's at a map pin. Its suggestions carry coordinates, so picking one costs no second call. Set
 * {@code OLA_MAPS_KEY}; the key stays on the server.
 */
@Component
public class OlaMapsClient implements GeoProvider {

    private static final Pattern PIN = Pattern.compile("\\b([1-9][0-9]{5})\\b");
    /** A reverse-geocode result this close to the pin is the building the pin is on, not just a nearby place. */
    private static final double SAME_PLACE_METRES = 15;

    private final String key;
    private final RestClient http;

    public OlaMapsClient(@Value("${medizon.location.ola.key:}") String key,
                         @Value("${medizon.location.ola.url:https://api.olamaps.io}") String url) {
        this.key = key.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Override public String name() { return "Ola Maps"; }

    @Override public boolean isConfigured() { return !key.isEmpty(); }

    @Override
    public List<GeoPlace> suggest(String q, int limit) {
        JsonNode body = call(() -> http.get().uri(u -> u.path("/places/v1/autocomplete")
                .queryParam("input", q).queryParam("api_key", key).build())
                .header("X-Request-Id", UUID.randomUUID().toString()).retrieve().body(JsonNode.class));
        List<GeoPlace> out = new ArrayList<>();
        if (body == null) return out;
        for (JsonNode p : body.path("predictions")) {
            GeoPlace place = fromPrediction(p);
            if (place != null && out.size() < limit) out.add(place);
        }
        return out;
    }

    @Override
    public GeoPlace reverse(double lat, double lon) {
        JsonNode body = call(() -> http.get().uri(u -> u.path("/places/v1/reverse-geocode")
                .queryParam("latlng", lat + "," + lon).queryParam("api_key", key).build())
                .header("X-Request-Id", UUID.randomUUID().toString()).retrieve().body(JsonNode.class));
        if (body == null) return null;
        JsonNode best = null;
        for (JsonNode r : body.path("results")) {
            if (best == null || r.path("distance_meters").asDouble(1e9) < best.path("distance_meters").asDouble(1e9)) best = r;
        }
        return best == null ? null : fromResult(best);
    }

    /**
     * A suggestion: "Arctica A Wing, Casa Rio, Lodha Palava" / "Trimurti Nagar, Dombivli East, ..., Maharashtra,
     * 421201, India". Street, area and city aren't marked, so it's flagged partial: the browser fills those from the
     * spot (reverse geocode) and India Post.
     */
    static GeoPlace fromPrediction(JsonNode p) {
        JsonNode loc = p.path("geometry").path("location");
        if (!loc.has("lat") || !loc.has("lng")) return null;
        double lat = loc.path("lat").asDouble(), lon = loc.path("lng").asDouble();
        if (!inIndia(lat, lon)) return null;
        JsonNode f = p.path("structured_formatting");
        String title = text(f, "main_text");
        String description = text(p, "description");
        if (title == null) title = description == null ? null : description.split(",")[0].trim();
        if (title == null) return null;
        String subtitle = clean(text(f, "secondary_text"));
        String pincode = lastPin(description);
        // The state is the term just before the PIN code
        String state = null;
        List<String> terms = new ArrayList<>();
        p.path("terms").forEach(t -> { String v = text(t, "value"); if (v != null) terms.add(v); });
        for (int i = 1; i < terms.size(); i++) if (terms.get(i).equals(pincode)) state = terms.get(i - 1);
        return new GeoPlace(title, subtitle, lat, lon, title, null, null, null, state, pincode, true);
    }

    /** What's at a spot, from address components; a named place only when the pin is on it. */
    static GeoPlace fromResult(JsonNode r) {
        JsonNode loc = r.path("geometry").path("location");
        if (!loc.has("lat") || !loc.has("lng")) return null;
        double lat = loc.path("lat").asDouble(), lon = loc.path("lng").asDouble();
        String road = null, neighbourhood = null, sublocality = null, city = null, district = null, state = null, pincode = null;
        for (JsonNode c : r.path("address_components")) {
            String v = text(c, "long_name");
            if (v == null) continue;
            for (JsonNode t : c.path("types")) {
                switch (t.asText()) {
                    case "street_address", "route" -> road = road == null ? v : road;
                    case "neighborhood" -> neighbourhood = neighbourhood == null ? v : neighbourhood;
                    case "sublocality", "sublocality_level_1" -> sublocality = sublocality == null ? v : sublocality;
                    case "locality" -> city = v;
                    case "administrative_area_level_2" -> district = v;
                    case "administrative_area_level_1" -> state = v;
                    case "postal_code" -> pincode = GeoPlace.pin(v);
                    default -> { }
                }
            }
        }
        if (pincode == null) pincode = lastPin(text(r, "formatted_address"));
        if (city == null) city = district;
        Set<String> areaParts = new LinkedHashSet<>();
        if (neighbourhood != null) areaParts.add(neighbourhood);
        if (sublocality != null) areaParts.add(sublocality);
        String area = areaParts.isEmpty() ? null : String.join(", ", areaParts);
        String name = text(r, "name");
        boolean onIt = r.path("distance_meters").asDouble(1e9) <= SAME_PLACE_METRES;
        String building = onIt ? name : null;
        String title = building != null ? building : road != null ? road : area != null ? area : city;
        if (title == null) return null;
        final String t = title;
        Set<String> rest = new LinkedHashSet<>();
        for (String s : new String[]{road, area, city, state}) if (s != null && !s.equals(t)) rest.add(s);
        return new GeoPlace(title, String.join(", ", rest), lat, lon, building, road, area, city, state, pincode, false);
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        if (!isConfigured()) throw new GeoUnavailableException("Ola Maps key not set");
        try {
            JsonNode body = request.get();
            String status = body == null ? null : body.path("status").asText(null);
            if (status != null && !"ok".equalsIgnoreCase(status) && !"zero_results".equalsIgnoreCase(status)) {
                throw new GeoUnavailableException("Ola Maps answered " + status + ": " + body.path("error_message").asText(""));
            }
            return body;
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (HttpClientErrorException e) {
            // 401 bad key, 403 not allowed, 429 over the rate or monthly limit
            throw new GeoUnavailableException("Ola Maps answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new GeoUnavailableException("Ola Maps failed: " + e.getMessage(), e);
        }
    }

    /** "Dombivli East, Maharashtra, 421201, India" without the country. */
    private static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll(",\\s*India\\s*$", "").trim();
        return t.isEmpty() ? null : t;
    }

    private static String lastPin(String s) {
        if (s == null) return null;
        Matcher m = PIN.matcher(s);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private static boolean inIndia(double lat, double lon) {
        return lat >= 6 && lat <= 37.5 && lon >= 68 && lon <= 97.5;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
