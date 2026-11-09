package com.medizon.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Roughly where an IP address is ("Mumbai, Maharashtra, India"), for new sign-in emails. Uses a free lookup service
 * (GEOIP_URL; freeipapi.com by default: free, no key, 60 lookups a minute). Best effort: a private address, a
 * slow or failing service, or GEOIP_URL=off just leaves the place out.
 */
@Slf4j
@Component
public class GeoIpClient {

    private final String url;
    private final RestClient http;

    public GeoIpClient(@Value("${medizon.geoip.url:https://free.freeipapi.com/api/v1/json/{ip}}") String url) {
        this.url = url == null ? "" : url.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public Optional<String> place(String ip) {
        if (url.isEmpty() || url.equalsIgnoreCase("off") || !url.contains("{ip}") || !isPublic(ip)) return Optional.empty();
        try {
            JsonNode j = http.get().uri(url, ip).header("Accept", "application/json").retrieve().body(JsonNode.class);
            if (j == null) return Optional.empty();
            // freeipapi.com: {"cityName": "Mumbai", "regionName": "Maharashtra", "countryName": "India", ...}; "-" when unknown
            Set<String> parts = new LinkedHashSet<>();
            for (String f : new String[]{"cityName", "regionName", "countryName"}) {
                String v = j.path(f).asText("").trim();
                if (!v.isEmpty() && !v.equals("-")) parts.add(v);
            }
            return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", parts));
        } catch (Exception e) {
            log.debug("IP lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The visitor's address: Render's proxy puts it first in X-Forwarded-For. */
    public static String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    /** A real internet address, not this machine or a home or office network. Never looks anything up in DNS. */
    static boolean isPublic(String ip) {
        if (ip == null || !ip.matches("[0-9a-fA-F:.]+")) return false;
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                    || a.isMulticastAddress()) return false;
            byte[] b = a.getAddress();
            if (b.length == 16 && (b[0] & 0xfe) == 0xfc) return false;          // IPv6 private (fc00::/7)
            if (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64) return false;   // carrier NAT 100.64/10
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
