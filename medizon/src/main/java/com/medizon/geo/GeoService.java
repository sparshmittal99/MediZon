package com.medizon.geo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Address lookups for the location picker and the address form: suggestions while typing, the address at a map pin,
 * and a PIN code's city, state and localities.
 *
 * <p>Geocoders are tried in order: Ola Maps (best for Indian addresses), then LocationIQ, then OpenStreetMap's
 * Nominatim. One is skipped while it has no key, has used its daily limit ({@code OLA_MAPS_DAILY_LIMIT},
 * {@code LOCATIONIQ_DAILY_LIMIT}) or failed in the last {@link #COOL_DOWN}. PIN codes come from India Post. Answers
 * are cached in memory, and each visitor gets {@value #PER_CLIENT} lookups per {@link #CLIENT_WINDOW}.
 *
 * <p>Usage is counted per day (India time) for Admin > Monitoring; the last {@value #DAYS_KEPT} days are kept in memory.
 */
@Slf4j
@Service
public class GeoService {

    static final int PER_CLIENT = 150;
    static final Duration CLIENT_WINDOW = Duration.ofMinutes(10);
    static final int DAYS_KEPT = 7;
    static final Duration COOL_DOWN = Duration.ofMinutes(2);
    private static final Duration BALANCE_FRESH = Duration.ofMinutes(1);
    private static final int SUGGESTIONS = 6;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    static final String INDIA_POST = "India Post";

    /** A geocoder in the chain with its daily limit (0 = none) and state. */
    private static final class Slot {
        final GeoProvider provider;
        final int dailyLimit;
        volatile Instant restUntil = Instant.EPOCH;
        volatile String lastProblem;
        volatile Integer balance;
        volatile Instant balanceAt = Instant.EPOCH;

        Slot(GeoProvider provider, int dailyLimit) { this.provider = provider; this.dailyLimit = dailyLimit; }
    }

    private final List<Slot> chain = new ArrayList<>();
    private final IndiaPostClient indiaPost;

    private final Map<String, List<GeoPlace>> suggestCache = lru(2_000);
    private final Map<String, Optional<GeoPlace>> reverseCache = lru(5_000);
    private final Map<String, Optional<IndiaPostClient.PincodeInfo>> pinCache = lru(5_000);
    private final Map<String, Deque<Instant>> clients = new ConcurrentHashMap<>();
    private final Map<LocalDate, Day> days = new ConcurrentHashMap<>();

    private volatile Instant indiaPostRestUntil = Instant.EPOCH;
    private volatile String lastProblem;
    private volatile LocalDateTime lastProblemAt;

    @Autowired
    public GeoService(OlaMapsClient ola, LocationIqClient locationIq, NominatimClient nominatim, IndiaPostClient indiaPost,
                      @Value("${medizon.location.ola.daily-limit:3200}") int olaLimit,
                      @Value("${medizon.location.locationiq.daily-limit:4800}") int locationIqLimit) {
        this(List.of(ola, locationIq, nominatim), List.of(olaLimit, locationIqLimit, 0), indiaPost);
    }

    /** The chain in order, each with its daily limit (0 = none). */
    GeoService(List<? extends GeoProvider> providers, List<Integer> limits, IndiaPostClient indiaPost) {
        for (int i = 0; i < providers.size(); i++) chain.add(new Slot(providers.get(i), limits.get(i)));
        this.indiaPost = indiaPost;
    }

    /** False when this visitor has made too many lookups lately; counts this one otherwise. */
    public boolean allow(String client) {
        Instant now = Instant.now();
        Deque<Instant> times = clients.computeIfAbsent(client == null ? "?" : client, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(CLIENT_WINDOW))) times.pollFirst();
            if (times.size() >= PER_CLIENT) {
                today().refused.incrementAndGet();
                return false;
            }
            times.addLast(now);
        }
        if (clients.size() > 20_000) clients.entrySet().removeIf(e -> { synchronized (e.getValue()) { return e.getValue().isEmpty(); } });
        return true;
    }

    /** Places matching what's typed: buildings, landmarks, streets, areas. Empty when nothing matches or lookups are down. */
    public List<GeoPlace> suggest(String query) {
        String q = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        if (q.length() < 3 || q.length() > 120) return List.of();
        String key = q.toLowerCase(Locale.ROOT);
        List<GeoPlace> hit = suggestCache.get(key);
        if (hit != null) { today().cached.incrementAndGet(); return hit; }
        Answer<List<GeoPlace>> a = firstAnswer(p -> p.suggest(q, SUGGESTIONS));
        if (a == null) return List.of();            // all down: don't cache, try again next time
        suggestCache.put(key, a.value());
        return a.value();
    }

    /** The address at a map pin or the device's location. */
    public Optional<GeoPlace> reverse(double lat, double lon) {
        // About 10 m: the same building gets the same answer
        String key = String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);
        Optional<GeoPlace> hit = reverseCache.get(key);
        if (hit != null) { today().cached.incrementAndGet(); return hit; }
        Answer<Optional<GeoPlace>> a = firstAnswer(p -> Optional.ofNullable(p.reverse(lat, lon)));
        if (a == null) return Optional.empty();
        reverseCache.put(key, a.value());
        return a.value();
    }

    /** City, state and post office localities of a PIN code from India Post; empty if unknown or India Post is down. */
    public Optional<IndiaPostClient.PincodeInfo> pincode(String pin) {
        String p = GeoPlace.pin(pin);
        if (p == null) return Optional.empty();
        Optional<IndiaPostClient.PincodeInfo> hit = pinCache.get(p);
        if (hit != null) { today().cached.incrementAndGet(); return hit; }
        if (Instant.now().isBefore(indiaPostRestUntil)) return Optional.empty();
        try {
            Optional<IndiaPostClient.PincodeInfo> info = Optional.ofNullable(indiaPost.lookup(p));
            today().count(INDIA_POST).incrementAndGet();
            pinCache.put(p, info);
            return info;
        } catch (GeoUnavailableException e) {
            today().failed(INDIA_POST).incrementAndGet();
            problem(e.getMessage());
            log.warn("{}; PIN code details off for {}", e.getMessage(), COOL_DOWN);
            indiaPostRestUntil = Instant.now().plus(COOL_DOWN);
            return Optional.empty();
        }
    }

    /** Which geocoder answers right now (the first usable one), for Admin > Monitoring. */
    public String provider() {
        return chain.stream().filter(this::usable).map(s -> s.provider.name()).findFirst().orElse("none");
    }

    /** Why the first configured geocoder isn't answering, or null when it is. Never calls out. */
    public String primaryOffReason() {
        Slot first = chain.stream().filter(s -> s.provider.isConfigured()).findFirst().orElse(null);
        return first == null ? "No geocoder configured" : offReason(first);
    }

    /** The geocoders MediZon prefers, in order, that have a key. */
    public boolean anyKeyedProvider() {
        return chain.stream().anyMatch(s -> s.dailyLimit > 0 && s.provider.isConfigured());
    }

    public boolean isIndiaPostResting() { return Instant.now().isBefore(indiaPostRestUntil); }

    // ------------------------------------------------------------------ Admin > Monitoring

    /**
     * One geocoder's state today.
     *
     * @param dailyLimit     lookups a day before the next one takes over; 0 = no limit
     * @param remainingToday what the service itself says is left today; null when it can't say
     * @param offReason      why it's skipped right now; null when it answers
     */
    public record ProviderStatus(String name, boolean configured, int dailyLimit, long usedToday, long failedToday,
                                 Integer remainingToday, String offReason, String lastProblem) {
        public int getUsedPercent() { return dailyLimit <= 0 ? 0 : (int) Math.min(100, usedToday * 100 / dailyLimit); }
        public boolean isLimited() { return dailyLimit > 0; }
    }

    /** One day's lookups by who answered them (geocoders and India Post), plus cache hits and refusals. */
    public record DayStats(LocalDate day, Map<String, Long> answered, Map<String, Long> failed, long cached, long refused) {
        public long getTotal() { return answered.values().stream().mapToLong(Long::longValue).sum() + cached; }
        public long getFailedTotal() { return failed.values().stream().mapToLong(Long::longValue).sum(); }
        public long answeredBy(String name) { return answered.getOrDefault(name, 0L); }
        public long failedBy(String name) { return failed.getOrDefault(name, 0L); }
    }

    public record Status(String provider, List<ProviderStatus> providers, boolean indiaPostResting,
                         String lastProblem, LocalDateTime lastProblemAt, List<DayStats> days) {
        public DayStats getToday() { return days.isEmpty() ? null : days.get(0); }
        /** Column names for the 7-day table: the geocoders in order, then India Post. */
        public List<String> getSources() {
            List<String> out = new ArrayList<>(providers.stream().map(ProviderStatus::name).toList());
            out.add(INDIA_POST);
            return out;
        }
    }

    /** Everything for Admin > Monitoring, including LocationIQ's own remaining count (one call a minute at most). */
    public Status status() {
        prune();
        Day t = today();
        List<ProviderStatus> providers = new ArrayList<>();
        for (Slot s : chain) {
            String name = s.provider.name();
            providers.add(new ProviderStatus(name, s.provider.isConfigured(), s.dailyLimit, t.count(name).get(),
                    t.failed(name).get(), balance(s), offReason(s), s.lastProblem));
        }
        List<DayStats> list = new ArrayList<>();
        days.entrySet().stream().sorted(Map.Entry.<LocalDate, Day>comparingByKey(Comparator.reverseOrder()))
                .forEach(e -> list.add(e.getValue().stats(e.getKey())));
        return new Status(provider(), providers, isIndiaPostResting(), lastProblem, lastProblemAt, list);
    }

    private Integer balance(Slot s) {
        if (!s.provider.isConfigured()) return null;
        if (Instant.now().isBefore(s.balanceAt.plus(BALANCE_FRESH))) return s.balance;
        s.balanceAt = Instant.now();
        try {
            s.balance = s.provider.remainingToday();
        } catch (GeoUnavailableException e) {
            log.warn("Couldn't read the {} balance: {}", s.provider.name(), e.getMessage());
        }
        return s.balance;
    }

    // ------------------------------------------------------------------ internals

    private record Answer<T>(T value) {}

    /** The first geocoder in the chain that answers; null when none could. */
    private <T> Answer<T> firstAnswer(Function<GeoProvider, T> lookup) {
        for (Slot s : chain) {
            if (!usable(s)) continue;
            String name = s.provider.name();
            try {
                T value = lookup.apply(s.provider);
                today().count(name).incrementAndGet();
                return new Answer<>(value);
            } catch (GeoUnavailableException e) {
                today().failed(name).incrementAndGet();
                s.lastProblem = e.getMessage();
                problem(e.getMessage());
                s.restUntil = Instant.now().plus(COOL_DOWN);
                log.warn("{}; skipping {} for {}", e.getMessage(), name, COOL_DOWN);
            }
        }
        return null;
    }

    private boolean usable(Slot s) {
        return offReason(s) == null;
    }

    private String offReason(Slot s) {
        String name = s.provider.name();
        if (!s.provider.isConfigured()) return "No " + name + " key set";
        if (Instant.now().isBefore(s.restUntil)) return name + " failed recently: " + s.lastProblem;
        if (s.dailyLimit > 0 && today().count(name).get() >= s.dailyLimit)
            return "Today's limit of " + String.format(Locale.ENGLISH, "%,d", s.dailyLimit) + " " + name + " lookups is used";
        return null;
    }

    private void problem(String message) {
        lastProblem = message;
        lastProblemAt = LocalDateTime.now(ZONE);
    }

    private Day today() {
        LocalDate d = LocalDate.now(ZONE);
        Day day = days.get(d);
        if (day == null) {
            prune();            // a new day: drop the oldest (not inside computeIfAbsent, which forbids changing the map)
            day = days.computeIfAbsent(d, k -> new Day());
        }
        return day;
    }

    private void prune() {
        LocalDate oldest = LocalDate.now(ZONE).minusDays(DAYS_KEPT - 1);
        days.keySet().removeIf(d -> d.isBefore(oldest));
    }

    private static final class Day {
        final Map<String, AtomicLong> answered = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> failed = new ConcurrentHashMap<>();
        final AtomicLong cached = new AtomicLong(), refused = new AtomicLong();

        AtomicLong count(String name) { return answered.computeIfAbsent(name, k -> new AtomicLong()); }
        AtomicLong failed(String name) { return failed.computeIfAbsent(name, k -> new AtomicLong()); }

        DayStats stats(LocalDate day) {
            Map<String, Long> a = new LinkedHashMap<>(), f = new LinkedHashMap<>();
            answered.forEach((k, v) -> a.put(k, v.get()));
            failed.forEach((k, v) -> f.put(k, v.get()));
            return new DayStats(day, a, f, cached.get(), refused.get());
        }
    }

    private static <V> Map<String, V> lru(int max) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> e) { return size() > max; }
        });
    }
}
