package com.medizon.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Performance monitoring: a log of slow requests, page load times measured in shoppers' browsers, and summaries of
 * the metrics Spring Boot Actuator collects (request timings per endpoint, scheduled jobs, JVM, database pool).
 * Everything is also on /actuator/prometheus for Grafana or any other Prometheus-compatible tool.
 */
@Service
@Slf4j
public class PerformanceMonitor {

    private static final int SLOW_LOG_SIZE = 200;
    /** Distinct pages tracked for browser timings; anything past this is grouped as "other". */
    private static final int MAX_PAGES = 150;

    public record SlowRequest(LocalDateTime at, String method, String path, int status, long millis, String user, String requestId) {}

    public record Endpoint(String method, String uri, long count, long serverErrors, double avgMs, double p95Ms, double maxMs,
                           double totalSeconds) {}

    public record PageTiming(String page, long count, double ttfbMs, double domReadyMs, double loadMs, double loadP95Ms) {}

    public record Job(String name, long count, long failures, double avgMs, double maxMs) {}

    private final MeterRegistry meters;
    private final long slowMs;
    private final Deque<SlowRequest> slow = new ArrayDeque<>();
    private final Set<String> pages = ConcurrentHashMap.newKeySet();

    public PerformanceMonitor(MeterRegistry meters, @Value("${medizon.monitoring.slow-request-ms:1000}") long slowMs) {
        this.meters = meters;
        this.slowMs = slowMs;
    }

    public long getSlowThresholdMs() { return slowMs; }

    // ------------------------------------------------------------------ recording

    void recordRequest(String method, String path, int status, long millis, String user, String requestId) {
        if (millis < slowMs || path.startsWith("/actuator")) return;
        log.warn("Slow request: {} {} took {} ms (status {})", method, path, millis, status);
        synchronized (slow) {
            slow.addFirst(new SlowRequest(LocalDateTime.now(), method, path, status, millis, user, requestId));
            while (slow.size() > SLOW_LOG_SIZE) slow.removeLast();
        }
    }

    /** Navigation timings from the browser, in milliseconds since the page was requested. */
    public void recordPage(String path, long ttfb, long domReady, long load) {
        if (load <= 0 || load > 120_000) return;      // tab left in the background, or a clock jump
        String page = pageKey(path);
        timer("medizon.browser.ttfb", page).record(Duration.ofMillis(Math.max(0, ttfb)));
        timer("medizon.browser.dom_ready", page).record(Duration.ofMillis(Math.max(0, domReady)));
        timer("medizon.browser.load", page).record(Duration.ofMillis(load));
    }

    private Timer timer(String name, String page) {
        return Timer.builder(name).tag("page", page).publishPercentiles(0.95).register(meters);
    }

    /** "/medicines/42" and "/medicines/57" are the same page: "/medicines/{id}". */
    String pageKey(String path) {
        String p = path == null || path.isBlank() ? "/" : path.replaceAll("[?#].*$", "");
        p = Arrays.stream(p.split("/")).map(s -> s.matches("\\d+|[0-9a-fA-F-]{16,}") ? "{id}" : s)
                .collect(Collectors.joining("/"));
        if (p.isEmpty()) p = "/";
        if (p.length() > 80) p = p.substring(0, 80);
        if (pages.contains(p)) return p;
        if (pages.size() >= MAX_PAGES) return "other";
        pages.add(p);
        return p;
    }

    // ------------------------------------------------------------------ reading

    public List<SlowRequest> slowRequests() {
        synchronized (slow) { return new ArrayList<>(slow); }
    }

    /** Request timings per endpoint since the app started; p95 and max cover the last few minutes. */
    public List<Endpoint> endpoints() {
        record Key(String method, String uri) {}
        Map<Key, List<Timer>> byEndpoint = new HashMap<>();
        for (Timer t : meters.find("http.server.requests").timers()) {
            String uri = t.getId().getTag("uri");
            if (uri == null || uri.startsWith("/actuator") || uri.equals("/**") || uri.equals("UNKNOWN")) continue;
            byEndpoint.computeIfAbsent(new Key(t.getId().getTag("method"), uri), k -> new ArrayList<>()).add(t);
        }
        List<Endpoint> out = new ArrayList<>();
        byEndpoint.forEach((k, timers) -> {
            long count = 0, errors = 0;
            double total = 0, max = 0, p95 = 0;
            for (Timer t : timers) {
                count += t.count();
                total += t.totalTime(TimeUnit.MILLISECONDS);
                max = Math.max(max, t.max(TimeUnit.MILLISECONDS));
                p95 = Math.max(p95, percentile(t, 0.95));
                if ("SERVER_ERROR".equals(t.getId().getTag("outcome"))) errors += t.count();
            }
            if (count > 0) out.add(new Endpoint(k.method(), k.uri(), count, errors, total / count, p95, max, total / 1000));
        });
        out.sort(Comparator.comparingDouble(Endpoint::totalSeconds).reversed());
        return out;
    }

    public List<PageTiming> pageTimings() {
        List<PageTiming> out = new ArrayList<>();
        for (Timer load : meters.find("medizon.browser.load").timers()) {
            String page = load.getId().getTag("page");
            Timer ttfb = meters.find("medizon.browser.ttfb").tag("page", page).timer();
            Timer dom = meters.find("medizon.browser.dom_ready").tag("page", page).timer();
            out.add(new PageTiming(page, load.count(), ttfb == null ? 0 : ttfb.mean(TimeUnit.MILLISECONDS),
                    dom == null ? 0 : dom.mean(TimeUnit.MILLISECONDS), load.mean(TimeUnit.MILLISECONDS), percentile(load, 0.95)));
        }
        out.sort(Comparator.comparingDouble(PageTiming::loadMs).reversed());
        return out;
    }

    /** Scheduled jobs (reminders, refills, expiry sweeps ...): how often they ran, how long they took, how many failed. */
    public List<Job> jobs() {
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, double[]> times = new HashMap<>();
        for (Timer t : meters.find("tasks.scheduled.execution").timers()) {
            String cls = Objects.toString(t.getId().getTag("code.namespace"), "");
            String name = cls.substring(cls.lastIndexOf('.') + 1) + "." + t.getId().getTag("code.function");
            long[] c = counts.computeIfAbsent(name, k -> new long[2]);
            c[0] += t.count();
            if (!"SUCCESS".equals(t.getId().getTag("outcome"))) c[1] += t.count();
            double[] d = times.computeIfAbsent(name, k -> new double[2]);
            d[0] += t.totalTime(TimeUnit.MILLISECONDS);
            d[1] = Math.max(d[1], t.max(TimeUnit.MILLISECONDS));
        }
        List<Job> out = new ArrayList<>();
        counts.forEach((name, c) -> out.add(new Job(name, c[0], c[1], c[0] == 0 ? 0 : times.get(name)[0] / c[0], times.get(name)[1])));
        return out;
    }

    /** JVM, CPU and database pool readings, keyed for the dashboard. */
    public Map<String, Double> system() {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("uptimeSeconds", gauge("process.uptime"));
        out.put("cpu", gauge("process.cpu.usage"));
        out.put("systemCpu", gauge("system.cpu.usage"));
        out.put("heapUsed", sum("jvm.memory.used", Tag.of("area", "heap")));
        out.put("heapMax", sum("jvm.memory.max", Tag.of("area", "heap")));
        out.put("threads", gauge("jvm.threads.live"));
        out.put("dbActive", gauge("hikaricp.connections.active"));
        out.put("dbIdle", gauge("hikaricp.connections.idle"));
        out.put("dbMax", gauge("hikaricp.connections.max"));
        out.put("dbPending", gauge("hikaricp.connections.pending"));
        Timer acquire = meters.find("hikaricp.connections.acquire").timer();
        out.put("dbAcquireMs", acquire == null ? 0 : acquire.mean(TimeUnit.MILLISECONDS));
        return out;
    }

    /** All requests: count, server errors and average time since the app started. */
    public Map<String, Double> requestTotals() {
        long count = 0, errors = 0;
        double total = 0;
        for (Timer t : meters.find("http.server.requests").timers()) {
            count += t.count();
            total += t.totalTime(TimeUnit.MILLISECONDS);
            if ("SERVER_ERROR".equals(t.getId().getTag("outcome"))) errors += t.count();
        }
        return Map.of("count", (double) count, "errors", (double) errors, "avgMs", count == 0 ? 0 : total / count,
                "errorRate", count == 0 ? 0 : errors * 100.0 / count);
    }

    private double gauge(String name) {
        Gauge g = meters.find(name).gauge();
        if (g != null) return g.value();
        var tg = meters.find(name).timeGauge();
        return tg == null ? 0 : tg.value(TimeUnit.SECONDS);
    }

    private double sum(String name, Tag tag) {
        return meters.find(name).tags(List.of(tag)).gauges().stream().mapToDouble(Gauge::value).filter(v -> v > 0).sum();
    }

    private static double percentile(Timer t, double p) {
        for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
            if (Math.abs(v.percentile() - p) < 0.001) return v.value(TimeUnit.MILLISECONDS);
        }
        return 0;
    }
}
