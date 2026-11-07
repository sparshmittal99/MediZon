package com.medizon.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Right after start-up, opens the busiest pages once from the server itself, in the background. The first request to
 * each page is the slow one (the JVM compiles the code, the database pool connects, the templates are compiled and
 * cached); this way that cost is paid before shoppers and uptime pings arrive, not by them. Sent with
 * {@link #HEADER}, so it isn't counted as a visit.
 */
@Slf4j
@Component
public class StartupWarmup {

    /** Marks the warm-up's own requests. */
    public static final String HEADER = "X-MediZon-Warmup";

    private static final List<String> PAGES = List.of("/", "/medicines", "/login", "/cart", "/help");

    private final Environment env;

    public StartupWarmup(Environment env) { this.env = env; }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        String port = env.getProperty("local.server.port");
        if (port == null) return;   // no web server (tests)
        Thread t = new Thread(() -> run("http://127.0.0.1:" + port), "startup-warmup");
        t.setDaemon(true);
        t.start();
    }

    private static void run(String base) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        long start = System.nanoTime();
        for (String page : PAGES) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + page)).timeout(Duration.ofSeconds(90))
                        .header(HEADER, "1").header("User-Agent", "MediZon-Warmup").GET().build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.debug("Warm-up of {} failed: {}", page, e.getMessage());
            }
        }
        log.info("Warmed up {} pages in {} ms", PAGES.size(), (System.nanoTime() - start) / 1_000_000);
    }
}
