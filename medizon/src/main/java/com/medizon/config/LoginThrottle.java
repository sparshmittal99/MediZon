package com.medizon.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing: after {@value #MAX_PER_ACCOUNT} wrong passwords for one email, or
 * {@value #MAX_PER_IP} from one address, sign-in is refused for that email or address until the oldest failure is
 * {@link #WINDOW} old. Emails that have no account are counted the same way, so a lock says nothing about whether an
 * account exists. Kept in memory: a restart clears it, and each instance counts on its own.
 */
@Component
public class LoginThrottle {

    static final int MAX_PER_ACCOUNT = 5;
    static final int MAX_PER_IP = 30;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginThrottle() { this(Clock.systemUTC()); }

    LoginThrottle(Clock clock) { this.clock = clock; }

    public boolean isBlocked(String email, String ip) {
        return count(accountKey(email)) >= MAX_PER_ACCOUNT || count(ipKey(ip)) >= MAX_PER_IP;
    }

    public void failed(String email, String ip) {
        Instant now = clock.instant();
        for (String key : new String[]{accountKey(email), ipKey(ip)}) {
            failures.compute(key, (k, times) -> {
                Deque<Instant> t = times == null ? new ArrayDeque<>() : times;
                prune(t, now);
                t.addLast(now);
                return t;
            });
        }
    }

    /** A correct password clears the account's count (not the address's, which may be guessing other accounts). */
    public void succeeded(String email) {
        failures.remove(accountKey(email));
    }

    private int count(String key) {
        Instant now = clock.instant();
        Deque<Instant> t = failures.computeIfPresent(key, (k, times) -> {
            prune(times, now);
            return times.isEmpty() ? null : times;
        });
        return t == null ? 0 : t.size();
    }

    /** Forgets failures older than the window, so the map doesn't grow. */
    @Scheduled(fixedDelay = 600_000)
    public void cleanUp() {
        Instant now = clock.instant();
        failures.replaceAll((k, times) -> { prune(times, now); return times; });
        failures.values().removeIf(Deque::isEmpty);
    }

    private void prune(Deque<Instant> times, Instant now) {
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(WINDOW))) times.pollFirst();
    }

    private static String accountKey(String email) {
        return "a:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }
}
