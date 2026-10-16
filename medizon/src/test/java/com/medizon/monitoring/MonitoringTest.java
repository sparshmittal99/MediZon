package com.medizon.monitoring;

import com.medizon.domain.AppError;
import com.medizon.domain.ErrorSeverity;
import com.medizon.domain.ErrorSource;
import com.medizon.domain.NotificationType;
import com.medizon.domain.Role;
import com.medizon.repository.AppErrorRepository;
import com.medizon.service.StaffAlerts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        ErrorMonitor monitor = new ErrorMonitor(null, null, meters, null, ErrorSeverity.HIGH);
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

    @Test
    void severityFollowsWhatFailed() {
        assertThat(ErrorMonitor.rate(error(ErrorSource.BROWSER, "JavaScript error", null, null, null))).isEqualTo(ErrorSeverity.LOW);
        assertThat(ErrorMonitor.rate(error(ErrorSource.SERVER, "java.lang.NullPointerException", "GET", 500, null)))
                .isEqualTo(ErrorSeverity.MEDIUM);
        assertThat(ErrorMonitor.rate(error(ErrorSource.SERVER, "java.lang.NullPointerException", "POST", 500, null)))
                .isEqualTo(ErrorSeverity.HIGH);
        assertThat(ErrorMonitor.rate(error(ErrorSource.SERVER, "java.net.ConnectException", "GET", 500,
                "org.springframework.transaction.CannotCreateTransactionException: Could not open JPA EntityManager")))
                .isEqualTo(ErrorSeverity.CRITICAL);
    }

    @Test
    void adminsAreAlertedOnceAboutANewSevereError() {
        Map<String, AppError> stored = new HashMap<>();
        AppErrorRepository repo = mock(AppErrorRepository.class);
        when(repo.findByFingerprint(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.<String>getArgument(0))));
        when(repo.save(any())).thenAnswer(i -> {
            AppError e = i.getArgument(0);
            if (e.getId() == null) e.setId((long) stored.size() + 1);
            stored.put(e.getFingerprint(), e);
            return e;
        });
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> i.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        StaffAlerts alerts = mock(StaffAlerts.class);
        ErrorMonitor monitor = new ErrorMonitor(repo, tx, new SimpleMeterRegistry(), alerts, ErrorSeverity.HIGH);

        HttpServletRequest checkout = mock(HttpServletRequest.class);
        when(checkout.getMethod()).thenReturn("POST");
        when(checkout.getRequestURI()).thenReturn("/checkout");
        for (int i = 0; i < 2; i++) {        // the same error twice: one alert
            monitor.capture(new IllegalStateException("stock went negative"), checkout, 500);
            monitor.flush();
        }

        verify(alerts, times(1)).notify(eq(Role.ADMIN), eq(NotificationType.ERROR_ALERT),
                contains("New error (High)"), contains("POST /checkout"), eq("/admin/monitoring#errors"));
        assertThat(stored.values()).singleElement().satisfies(e -> {
            assertThat(e.getSeverity()).isEqualTo(ErrorSeverity.HIGH);
            assertThat(e.getOccurrences()).isEqualTo(2);
        });

        HttpServletRequest page = mock(HttpServletRequest.class);
        when(page.getMethod()).thenReturn("GET");
        when(page.getRequestURI()).thenReturn("/medicines");
        monitor.capture(new IllegalArgumentException("bad sort"), page, 500);              // medium: listed, not alerted
        monitor.flush();
        verifyNoMoreInteractions(alerts);
    }

    @Test
    void clientDisconnectsAreRecognised() {
        assertThat(RequestMonitorFilter.isClientDisconnect(new ClientAbortException(new IOException("Connection reset by peer")))).isTrue();
        assertThat(RequestMonitorFilter.isClientDisconnect(new IOException("Broken pipe"))).isTrue();
        assertThat(RequestMonitorFilter.isClientDisconnect(
                new IOException("An established connection was aborted by the software in your host machine"))).isTrue();
        assertThat(RequestMonitorFilter.isClientDisconnect(new IOException("No space left on device"))).isFalse();
        assertThat(RequestMonitorFilter.isClientDisconnect(new IllegalStateException("boom"))).isFalse();
    }

    private static AppError error(ErrorSource source, String type, String method, Integer status, String stack) {
        AppError e = new AppError();
        e.setSource(source);
        e.setType(type);
        e.setMethod(method);
        e.setStatus(status);
        e.setStackTrace(stack);
        return e;
    }
}
