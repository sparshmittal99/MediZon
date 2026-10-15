package com.medizon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medizon.monitoring.ErrorMonitor;
import com.medizon.monitoring.PerformanceMonitor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where browsers report JavaScript errors and page load times (medizon.js). Bodies are sent as text with
 * navigator.sendBeacon, which can't set a JSON content type or the CSRF header; nothing here changes shop data.
 * Each client may report a limited number of errors per ten minutes.
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringApi {

    private static final int MAX_BODY = 20_000;
    private static final int ERRORS_PER_WINDOW = 30;
    private static final long WINDOW_MS = 10 * 60_000;

    private final ErrorMonitor errors;
    private final PerformanceMonitor performance;
    private final ObjectMapper mapper;
    /** Client address to [window start, errors reported in it]. */
    private final Map<String, long[]> budget = new ConcurrentHashMap<>();

    @PostMapping("/client-error")
    public ResponseEntity<Void> clientError(@RequestBody(required = false) String body, HttpServletRequest request) {
        JsonNode e = parse(body);
        String message = text(e, "message", 1000);
        if (message == null || message.equals("Script error.") || !allowed(request)) return ResponseEntity.noContent().build();
        errors.captureBrowser(text(e, "kind", 20), message, text(e, "source", 300),
                e.hasNonNull("line") ? e.get("line").asInt() : null, e.hasNonNull("column") ? e.get("column").asInt() : null,
                text(e, "stack", 8000), text(e, "page", 300), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/page-timing")
    public ResponseEntity<Void> pageTiming(@RequestBody(required = false) String body) {
        JsonNode t = parse(body);
        String page = text(t, "page", 300);
        if (page != null && page.startsWith("/")) {
            performance.recordPage(page, t.path("ttfb").asLong(), t.path("domReady").asLong(), t.path("load").asLong());
        }
        return ResponseEntity.noContent().build();
    }

    private boolean allowed(HttpServletRequest request) {
        if (budget.size() > 10_000) budget.clear();
        long now = System.currentTimeMillis();
        long[] b = budget.compute(request.getRemoteAddr(), (k, v) -> v == null || now - v[0] > WINDOW_MS ? new long[]{now, 0} : v);
        synchronized (b) {
            return ++b[1] <= ERRORS_PER_WINDOW;
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY) return mapper.createObjectNode();
        try {
            JsonNode n = mapper.readTree(body);
            return n != null && n.isObject() ? n : mapper.createObjectNode();
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String text(JsonNode n, String field, int max) {
        if (!n.hasNonNull(field)) return null;
        String s = n.get(field).asText().strip();
        if (s.isEmpty()) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
