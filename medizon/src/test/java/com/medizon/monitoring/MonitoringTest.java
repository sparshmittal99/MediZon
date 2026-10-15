package com.medizon.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringTest {

    @Test
    void pagesWithIdsAreGroupedTogether() {
        PerformanceMonitor p = new PerformanceMonitor(new SimpleMeterRegistry(), 1000);
        assertThat(p.pageKey("/medicines/42")).isEqualTo("/medicines/{id}");
        assertThat(p.pageKey("/medicines/57?q=x")).isEqualTo("/medicines/{id}");
        assertThat(p.pageKey("/orders/7/invoice")).isEqualTo("/orders/{id}/invoice");
        assertThat(p.pageKey("/")).isEqualTo("/");
    }

    @Test
    void pageTimingsAreKeptPerPage() {
        PerformanceMonitor p = new PerformanceMonitor(new SimpleMeterRegistry(), 1000);
        p.recordPage("/medicines/1", 100, 400, 900);
        p.recordPage("/medicines/2", 300, 600, 1100);
        p.recordPage("/cart", 50, 200, 0);              // never finished loading: ignored
        assertThat(p.pageTimings()).singleElement().satisfies(t -> {
            assertThat(t.page()).isEqualTo("/medicines/{id}");
            assertThat(t.count()).isEqualTo(2);
            assertThat(t.loadMs()).isEqualTo(1000);
        });
    }

    @Test
    void slowRequestsAreLogged() {
        PerformanceMonitor p = new PerformanceMonitor(new SimpleMeterRegistry(), 1000);
        p.recordRequest("GET", "/medicines", 200, 150, null, "a1");
        p.recordRequest("POST", "/checkout", 302, 2400, "asha@example.com", "b2");
        assertThat(p.slowRequests()).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo("/checkout");
            assertThat(s.millis()).isEqualTo(2400);
        });
    }

    @Test
    void anExceptionLoggedOnItsWayOutIsCountedOnce() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ErrorMonitor monitor = new ErrorMonitor(null, null, meters);
        RuntimeException cause = new IllegalStateException("template broke");
        RuntimeException wrapped = new RuntimeException("Request processing failed", cause);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.thymeleaf.TemplateEngine");
        monitor.capture(new ch.qos.logback.classic.spi.LoggingEvent("x", logger, ch.qos.logback.classic.Level.ERROR,
                "Exception processing template", cause, null));                       // the library logs it first
        monitor.capture(wrapped, null, 500);                                           // then it escapes the request

        assertThat(meters.counter("medizon.errors", "source", "SERVER").count()).isEqualTo(1);
        assertThat(monitor.queued()).isEqualTo(2);                                     // the second only adds request details
    }

    @Test
    void errorsGroupByWhereTheyHappened() {
        StackTraceElement[] frames = {
                new StackTraceElement("org.hibernate.Session", "load", "Session.java", 10),
                new StackTraceElement("com.medizon.service.OrderService", "place", "OrderService.java", 88),
        };
        assertThat(ErrorMonitor.location(frames)).isEqualTo("OrderService.place:88");
        assertThat(ErrorMonitor.fingerprint("SERVER", "java.lang.IllegalStateException", "OrderService.place:88"))
                .isEqualTo(ErrorMonitor.fingerprint("SERVER", "java.lang.IllegalStateException", "OrderService.place:88"))
                .isNotEqualTo(ErrorMonitor.fingerprint("SERVER", "java.lang.IllegalStateException", "CartService.add:12"))
                .hasSize(40);
    }
}
