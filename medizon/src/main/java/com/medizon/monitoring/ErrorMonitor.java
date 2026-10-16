package com.medizon.monitoring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.medizon.domain.AppError;
import com.medizon.domain.ErrorSeverity;
import com.medizon.domain.ErrorSource;
import com.medizon.domain.NotificationType;
import com.medizon.domain.Role;
import com.medizon.repository.AppErrorRepository;
import com.medizon.service.StaffAlerts;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Error monitoring, in the style of Sentry: unhandled request exceptions, everything logged at ERROR (scheduled jobs,
 * the assistant, Hibernate ...) and errors from shoppers' browsers are grouped by fingerprint into {@link AppError}
 * rows that admins see under Admin > Monitoring.
 *
 * Capturing never touches the database: occurrences go on a bounded queue and are written in batches by
 * {@link #flush()}, so a failing database or a burst of errors can't slow down or break a request.
 *
 * Each error is rated with an {@link ErrorSeverity}, and admins get a notification (and an email) when one at or above
 * {@code medizon.monitoring.alert-severity} first happens, comes back after being resolved, or gets worse.
 */
@Service
@Slf4j
public class ErrorMonitor {

    private static final int QUEUE_SIZE = 2_000;
    private static final int MAX_STACK = 16_000;
    private static final String OWN_PACKAGE = "com.medizon.";
    private static final String MONITOR_PACKAGE = "com.medizon.monitoring.";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /**
     * Anywhere in the stack trace, these mean the database or the JVM itself is failing, so nearly every page is.
     * (The root cause alone can't tell: a database that's down and an assistant that's down are both a ConnectException.)
     */
    private static final List<String> CRITICAL_MARKERS = List.of(
            "java.lang.OutOfMemoryError", "CannotCreateTransactionException", "CannotGetJdbcConnectionException",
            "SQLTransientConnectionException", "JDBCConnectionException");
    /** An error alerts admins at most once in this long, however often it flaps between resolved and open. */
    private static final Duration ALERT_COOLDOWN = Duration.ofHours(1);
    /** At most this many alerts an hour in all, so a burst of different errors can't flood the admins' inbox. */
    private static final int MAX_ALERTS_PER_HOUR = 10;

    /** One occurrence, captured on the thread where it happened. */
    record Occurrence(ErrorSource source, String fingerprint, String type, String message, String location, String stack,
                      String path, String method, Integer status, String user, String userAgent, String requestId,
                      LocalDateTime at, boolean counted) {}

    private final AppErrorRepository errors;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final StaffAlerts staffAlerts;
    private final ErrorSeverity alertSeverity;
    private final Deque<LocalDateTime> recentAlerts = new ArrayDeque<>();
    private final BlockingQueue<Occurrence> queue = new LinkedBlockingQueue<>(QUEUE_SIZE);
    /**
     * Exceptions already captured, so one failure is counted once: a library often logs an exception (captured from
     * the log) before it reaches the request filter, and Tomcat logs it again after.
     */
    private final Set<Throwable> captured = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final AtomicLong dropped = new AtomicLong();

    public ErrorMonitor(AppErrorRepository errors, TransactionTemplate tx, MeterRegistry meters, StaffAlerts staffAlerts,
                        @Value("${medizon.monitoring.alert-severity:HIGH}") ErrorSeverity alertSeverity) {
        this.errors = errors;
        this.tx = tx;
        this.meters = meters;
        this.staffAlerts = staffAlerts;
        this.alertSeverity = alertSeverity;
    }

    // ------------------------------------------------------------------ capture

    /** An exception that escaped a request. */
    public void capture(Throwable t, HttpServletRequest request, int status) {
        Throwable root = rootCause(t);
        // Already logged on its way here: record the request's details on the same error without counting it again
        boolean counted = !alreadyCaptured(t);
        markCaptured(t);
        String location = location(root.getStackTrace());
        enqueue(new Occurrence(ErrorSource.SERVER, fingerprint("SERVER", root.getClass().getName(), location),
                root.getClass().getName(), root.getMessage(), location, stackTrace(t),
                request == null ? null : request.getRequestURI(), request == null ? null : request.getMethod(), status,
                currentUser(), request == null ? null : request.getHeader("User-Agent"), MDC.get(RequestMonitorFilter.MDC_KEY),
                LocalDateTime.now(), counted));
    }

    /** Anything logged at ERROR, with or without an exception. */
    void capture(ILoggingEvent e) {
        if (e.getLoggerName().startsWith(MONITOR_PACKAGE)) return;
        IThrowableProxy proxy = e.getThrowableProxy();
        Throwable t = proxy instanceof ThrowableProxy tp ? tp.getThrowable() : null;
        if (t != null) {
            if (alreadyCaptured(t)) return;     // already counted with its request
            markCaptured(t);
        }

        String type, location, stack;
        if (proxy != null) {
            IThrowableProxy root = proxy;
            while (root.getCause() != null) root = root.getCause();
            type = root.getClassName();
            location = location(root.getStackTraceElementProxyArray());
            stack = e.getFormattedMessage() + "\n" + ThrowableProxyUtil.asString(proxy);
        } else {
            type = e.getLoggerName();
            location = e.getLoggerName();
            stack = e.getFormattedMessage();
        }
        // Group by where it happened; logged messages without an exception group by their pattern, not the values in it
        String key = proxy != null ? location : e.getMessage();
        Map<String, String> mdc = e.getMDCPropertyMap();
        enqueue(new Occurrence(ErrorSource.SERVER, fingerprint(proxy != null ? "SERVER" : "LOG", type, key), type,
                proxy != null ? Objects.toString(rootMessage(proxy), e.getFormattedMessage()) : e.getFormattedMessage(),
                location, stack, mdc.get(RequestMonitorFilter.MDC_PATH), mdc.get(RequestMonitorFilter.MDC_METHOD), null,
                mdc.get(RequestMonitorFilter.MDC_USER), null, mdc.get(RequestMonitorFilter.MDC_KEY),
                LocalDateTime.now(), true));
    }

    /** A JavaScript error reported by a shopper's browser. */
    public void captureBrowser(String kind, String message, String source, Integer line, Integer column, String stack,
                               String page, HttpServletRequest request) {
        String script = source == null ? "" : source.replaceAll("[?#].*$", "");
        String location = script.isEmpty() ? null : script.substring(script.lastIndexOf('/') + 1) + (line == null ? "" : ":" + line
                + (column == null ? "" : ":" + column));
        String type = "promise".equals(kind) ? "Unhandled promise rejection"
                : "csp".equals(kind) ? "Content-Security-Policy violation" : "JavaScript error";
        enqueue(new Occurrence(ErrorSource.BROWSER, fingerprint("BROWSER", message, location == null ? page : location),
                type, message, location, stack, page, "GET", null, currentUser(),
                request.getHeader("User-Agent"), null, LocalDateTime.now(), true));
    }

    private void enqueue(Occurrence o) {
        if (o.counted()) meters.counter("medizon.errors", "source", o.source().name()).increment();
        if (!queue.offer(o)) dropped.incrementAndGet();
    }

    // ------------------------------------------------------------------ storage

    /** Writes queued occurrences: one row per fingerprint, counting repeats and keeping the latest details. */
    @Scheduled(fixedDelay = 3_000, initialDelay = 5_000)
    public void flush() {
        if (queue.isEmpty()) return;
        List<Occurrence> batch = new ArrayList<>();
        queue.drainTo(batch, 500);
        Map<String, List<Occurrence>> byFingerprint = new LinkedHashMap<>();
        batch.forEach(o -> byFingerprint.computeIfAbsent(o.fingerprint(), k -> new ArrayList<>()).add(o));
        List<Alert> alerts = new ArrayList<>();
        byFingerprint.forEach((fp, list) -> {
            try {
                Alert a;
                try {
                    a = tx.execute(s -> save(fp, list));
                } catch (DataIntegrityViolationException race) {
                    a = tx.execute(s -> save(fp, list));     // another instance created the row first
                }
                if (a != null) alerts.add(a);
            } catch (RuntimeException e) {
                log.warn("Couldn't store {} error occurrence(s): {}", list.size(), e.getMessage());
            }
        });
        long lost = dropped.getAndSet(0);
        if (lost > 0) log.warn("Error monitoring queue was full; {} occurrence(s) weren't stored", lost);
        alerts.forEach(this::send);      // after the errors are stored, so a failing alert can't lose them
    }

    /** Stores the occurrences; returns the alert to send, if this one should reach the admins. */
    private Alert save(String fingerprint, List<Occurrence> list) {
        Occurrence latest = list.get(list.size() - 1);
        AppError e = errors.findByFingerprint(fingerprint).orElseGet(() -> {
            AppError n = new AppError();
            n.setFingerprint(fingerprint);
            n.setSource(latest.source());
            n.setFirstSeen(list.get(0).at());
            return n;
        });
        e.setOccurrences(e.getOccurrences() + list.stream().filter(Occurrence::counted).count());
        e.setType(cut(latest.type(), 200));
        e.setMessage(cut(latest.message(), 1000));
        e.setLocation(cut(latest.location(), 300));
        e.setStackTrace(cut(latest.stack(), MAX_STACK));
        // A logged occurrence may lack the request; keep what the request filter knew about it
        if (latest.path() != null) e.setPath(cut(latest.path(), 300));
        if (latest.method() != null) e.setMethod(cut(latest.method(), 10));
        if (latest.status() != null) e.setStatus(latest.status());
        if (latest.user() != null) e.setLastUser(cut(latest.user(), 120));
        if (latest.userAgent() != null) e.setUserAgent(cut(latest.userAgent(), 300));
        if (latest.requestId() != null) e.setLastRequestId(cut(latest.requestId(), 16));
        e.setLastSeen(latest.at());
        boolean isNew = e.getId() == null;
        boolean reopened = e.isResolved();
        if (reopened) {        // it came back
            e.setResolved(false);
            e.setResolvedAt(null);
        }
        ErrorSeverity before = e.getSeverity();
        if (!e.isSeverityPinned()) e.setSeverity(rate(e));
        boolean worse = before != null && e.getSeverity() != before && e.getSeverity().atLeast(before);

        Alert alert = null;
        String why = isNew ? "New error" : reopened ? "Error is back" : worse ? "Error got worse" : null;
        if (why != null && e.getSeverity().atLeast(alertSeverity)
                && (e.getAlertedAt() == null || e.getAlertedAt().isBefore(latest.at().minus(ALERT_COOLDOWN)))
                && takeAlertSlot(latest.at())) {
            e.setAlertedAt(latest.at());
            alert = new Alert(why, e.getSeverity(), shortType(e.getType()), e.getMessage(),
                    e.getPath() != null ? (e.getMethod() != null ? e.getMethod() + " " : "") + e.getPath() : e.getLocation(),
                    e.getOccurrences());
        }
        errors.save(e);
        return alert;
    }

    // ------------------------------------------------------------------ severity and alerts

    /**
     * Critical: the database or the JVM is failing, so nearly everything is.
     * High: a request that changes something (placing an order, uploading a prescription ...) failed.
     * Medium: a page failed to load, or a scheduled job or library logged an error.
     * Low: an error in a shopper's browser.
     */
    public static ErrorSeverity rate(AppError e) {
        if (e.getSource() == ErrorSource.BROWSER) return ErrorSeverity.LOW;
        String evidence = Objects.toString(e.getType(), "") + "\n" + Objects.toString(e.getStackTrace(), "");
        if (CRITICAL_MARKERS.stream().anyMatch(evidence::contains)) return ErrorSeverity.CRITICAL;
        if (e.getStatus() != null && e.getStatus() >= 500 && e.getMethod() != null && !SAFE_METHODS.contains(e.getMethod())) {
            return ErrorSeverity.HIGH;
        }
        return ErrorSeverity.MEDIUM;
    }

    /** What admins are told about one error. */
    record Alert(String why, ErrorSeverity severity, String type, String message, String where, long occurrences) {}

    private boolean takeAlertSlot(LocalDateTime now) {
        synchronized (recentAlerts) {
            while (!recentAlerts.isEmpty() && recentAlerts.peekFirst().isBefore(now.minusHours(1))) recentAlerts.pollFirst();
            if (recentAlerts.size() >= MAX_ALERTS_PER_HOUR) {
                log.warn("Error alert skipped: {} already sent in the last hour (it's still listed under Admin > Monitoring)",
                        MAX_ALERTS_PER_HOUR);
                return false;
            }
            recentAlerts.addLast(now);
            return true;
        }
    }

    private void send(Alert a) {
        String message = a.severity().getLabel() + " severity. " + a.type()
                + (a.message() != null ? ": " + cut(a.message(), 300) : "")
                + (a.where() != null ? " (at " + a.where() + ")" : "")
                + ". Seen " + a.occurrences() + (a.occurrences() == 1 ? " time." : " times.");
        try {
            staffAlerts.notify(Role.ADMIN, NotificationType.ERROR_ALERT, a.why() + " (" + a.severity().getLabel() + "): " + a.type(),
                    message, "/admin/monitoring#errors");
        } catch (RuntimeException e) {
            log.warn("Couldn't alert admins about {}: {}", a.type(), e.getMessage());
        }
    }

    /** Rates errors stored before severities existed. */
    @EventListener(ApplicationReadyEvent.class)
    public void rateUnrated() {
        try {
            tx.executeWithoutResult(s -> errors.findBySeverityIsNull().forEach(e -> e.setSeverity(rate(e))));
        } catch (RuntimeException e) {
            log.warn("Couldn't rate stored errors: {}", e.getMessage());
        }
    }

    /** Resolved errors that haven't come back in 90 days are deleted, nightly. */
    @Scheduled(cron = "0 30 3 * * *")
    public void purge() {
        Integer n = tx.execute(s -> errors.deleteResolvedBefore(LocalDateTime.now().minusDays(90)));
        if (n != null && n > 0) log.info("Deleted {} old resolved error(s)", n);
    }

    public int queued() { return queue.size(); }

    // ------------------------------------------------------------------ log appender

    /** Hooks into Logback once the app is up, so every ERROR log line reaches monitoring. */
    @EventListener(ApplicationReadyEvent.class)
    public void attachToLogging() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) return;
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(Appender.NAME) != null) return;    // devtools restarts the context, not the JVM
        Appender appender = new Appender(this);
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    static final class Appender extends AppenderBase<ILoggingEvent> {
        static final String NAME = "MEDIZON_MONITOR";
        private final ErrorMonitor monitor;

        Appender(ErrorMonitor monitor) {
            this.monitor = monitor;
            setName(NAME);
        }

        @Override
        protected void append(ILoggingEvent e) {
            if (!e.getLevel().isGreaterOrEqual(Level.ERROR)) return;
            try {
                monitor.capture(e);
            } catch (RuntimeException ignored) {
                // monitoring must never break logging
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The innermost cause: errors group by what actually went wrong, however many layers wrapped it. */
    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        for (int i = 0; root.getCause() != null && root.getCause() != root && i < 20; i++) root = root.getCause();
        return root;
    }

    private boolean alreadyCaptured(Throwable t) {
        for (int i = 0; t != null && i < 20; i++, t = t.getCause() == t ? null : t.getCause()) {
            if (captured.contains(t)) return true;
        }
        return false;
    }

    private void markCaptured(Throwable t) {
        for (int i = 0; t != null && i < 20; i++, t = t.getCause() == t ? null : t.getCause()) captured.add(t);
    }

    private static String rootMessage(IThrowableProxy p) {
        while (p.getCause() != null) p = p.getCause();
        return p.getMessage();
    }

    /** The first frame in MediZon's own code, else the top frame. */
    static String location(StackTraceElement[] frames) {
        if (frames == null || frames.length == 0) return null;
        for (StackTraceElement f : frames) {
            if (f.getClassName().startsWith(OWN_PACKAGE) && !f.getClassName().startsWith(MONITOR_PACKAGE)) return frame(f);
        }
        return frame(frames[0]);
    }

    private static String location(StackTraceElementProxy[] frames) {
        if (frames == null) return null;
        StackTraceElement[] plain = new StackTraceElement[frames.length];
        for (int i = 0; i < frames.length; i++) plain[i] = frames[i].getStackTraceElement();
        return location(plain);
    }

    private static String frame(StackTraceElement f) {
        String cls = f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1);
        return cls + "." + f.getMethodName() + (f.getLineNumber() > 0 ? ":" + f.getLineNumber() : "");
    }

    private static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    static String fingerprint(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            for (String p : parts) sha.update((Objects.toString(p, "") + "\u0000").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a instanceof AnonymousAuthenticationToken || !a.isAuthenticated() ? null : a.getName();
    }

    private static String shortType(String type) {
        return type == null ? "Error" : type.substring(type.lastIndexOf('.') + 1);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
