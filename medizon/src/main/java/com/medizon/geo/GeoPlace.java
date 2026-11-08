package com.medizon.geo;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One place from the geocoder (LocationIQ, or OpenStreetMap's Nominatim when LocationIQ is off): a search suggestion,
 * or what's at a spot on the map. The flat or house number is never filled in: customers type it themselves.
 *
 * @param title    what the customer recognises: a building, landmark or street ("Phoenix Marketcity")
 * @param subtitle the rest of the address ("LBS Marg, Kurla West, Mumbai, Maharashtra")
 * @param building a named building, society or landmark at that spot, if any
 * @param road     street name
 * @param area     locality, neighbourhood or suburb
 * @param pincode  6 digit PIN code, or null when the map has none here
 * @param partial  street, area and city aren't known yet (an Ola Maps suggestion): the browser fills them from the
 *                 address at the spot and from India Post
 */
public record GeoPlace(String title, String subtitle, double lat, double lon,
                       String building, String road, String area, String city, String state, String pincode,
                       boolean partial) {

    /** Reads a Nominatim-style result (LocationIQ answers in the same shape). Null when it has no coordinates. */
    static GeoPlace from(JsonNode n) {
        if (n == null || !n.hasNonNull("lat") || !n.hasNonNull("lon")) return null;
        double lat, lon;
        try {
            lat = Double.parseDouble(n.get("lat").asText());
            lon = Double.parseDouble(n.get("lon").asText());
        } catch (NumberFormatException e) {
            return null;
        }
        JsonNode a = n.path("address");
        String road = first(a, "road", "pedestrian", "street");
        String area = first(a, "neighbourhood", "suburb", "quarter", "residential", "city_district", "hamlet", "village");
        String city = first(a, "city", "town", "municipality", "village", "state_district", "county");
        String state = first(a, "state");
        String pincode = pin(first(a, "postcode"));
        // A named place at this spot: LocationIQ's "name", or Nominatim's specific kinds
        String building = first(a, "name", "building", "amenity", "shop", "office", "tourism", "leisure", "hospital",
                "school", "college", "university", "mall", "commercial", "industrial");
        if (building == null) {
            String own = text(n, "name");
            if (own != null && !own.equals(road) && !own.equals(area) && !own.equals(city)) building = own;
        }

        String display = text(n, "display_name");
        String title = text(n, "display_place");
        String subtitle = text(n, "display_address");
        if (title == null) {
            title = building != null ? building : road != null ? road : area != null ? area : city;
            if (title == null && display != null) title = display.split(",")[0].trim();
        }
        if (subtitle == null) {
            final String t = title;
            Set<String> parts = Stream.of(building, road, area, city, state)
                    .filter(s -> s != null && !s.isBlank() && !s.equals(t))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            subtitle = String.join(", ", parts);
        }
        if (title == null) return null;
        return new GeoPlace(title, subtitle, lat, lon, building, road, area, city, state, pincode, false);
    }

    /** India's bounding box as "west,north,east,south" (the geocoders' viewbox order: lon1,lat1,lon2,lat2). */
    static final String INDIA_VIEWBOX = "68.0,37.5,97.5,6.0";

    /** Whether a result is in India: its country code when it has one, and its coordinates either way. */
    static boolean inIndia(JsonNode n) {
        if (n == null) return false;
        String cc = text(n.path("address"), "country_code");
        if (cc != null && !cc.equalsIgnoreCase("in")) return false;
        try {
            double lat = Double.parseDouble(n.path("lat").asText("x"));
            double lon = Double.parseDouble(n.path("lon").asText("x"));
            return lat >= 6 && lat <= 37.5 && lon >= 68 && lon <= 97.5;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** "400070" from "400 070" or "400070"; null for anything that isn't an Indian PIN code. */
    static String pin(String s) {
        if (s == null) return null;
        String p = s.replaceAll("\\s+", "");
        return p.matches("[1-9][0-9]{5}") ? p : null;
    }

    private static String first(JsonNode a, String... keys) {
        for (String k : keys) {
            String v = text(a, k);
            if (v != null) return v;
        }
        return null;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
