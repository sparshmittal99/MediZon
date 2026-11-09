package com.medizon.service;

import com.medizon.domain.NotificationDevice;
import com.medizon.domain.SignIn;
import com.medizon.domain.User;
import com.medizon.repository.NotificationDeviceRepository;
import com.medizon.repository.SignInRepository;
import com.medizon.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where an account is signed in, one row per browser or phone, and ending those sign-ins.
 * <p>
 * Every sign-in session carries the account's {@code rememberEpoch} from when it started (SignInGuard checks it on
 * each request). Ending sign-ins bumps the epoch, which ends every session and remember-me cookie at once, however
 * the device signed in: a password, or a remember-me cookie that the server's session registry never sees (the
 * Android app). Phones also lose their alarm sync token, and are woken by a push to drop their alarms right away.
 */
@Service
public class SignInService {

    /** Session attribute: the account's rememberEpoch when this session signed in. */
    public static final String EPOCH = "mz.signIn.epoch";
    /** Session attribute: when this session last updated its sign-in row. */
    private static final String SEEN = "mz.signIn.seen";
    private static final Duration SEEN_EVERY = Duration.ofMinutes(5);

    private final SignInRepository signIns;
    private final UserRepository users;
    private final NotificationDeviceRepository devices;
    private final PushService push;
    private final TrustedDeviceService trustedDevices;
    private final Duration sessionTimeout;

    public SignInService(SignInRepository signIns, UserRepository users, NotificationDeviceRepository devices,
                         PushService push, TrustedDeviceService trustedDevices,
                         @Value("${server.servlet.session.timeout:30m}") Duration sessionTimeout) {
        this.signIns = signIns;
        this.users = users;
        this.devices = devices;
        this.push = push;
        this.trustedDevices = trustedDevices;
        this.sessionTimeout = sessionTimeout;
    }

    /**
     * A signed-in request from this device: records the sign-in the first time a session is seen, then keeps it
     * fresh every few minutes. {@code rememberedUntil} is when the device's remember-me cookie expires, if it has one.
     */
    @Transactional
    public void seen(Long userId, HttpServletRequest request, HttpServletResponse response, LocalDateTime rememberedUntil) {
        HttpSession session = request.getSession(false);
        if (session == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (session.getAttribute(SEEN) instanceof LocalDateTime last && last.isAfter(now.minus(SEEN_EVERY))) return;
        session.setAttribute(SEEN, now);

        User user = users.getReferenceById(userId);
        String key = DeviceService.key(request, response);
        SignIn s = signIns.findByUserAndDeviceKey(user, key).orElseGet(() -> {
            SignIn n = new SignIn();
            n.setUser(user);
            n.setDeviceKey(key);
            n.setLabel(DeviceService.describe(request.getHeader(HttpHeaders.USER_AGENT)));
            return n;
        });
        String better = DeviceService.renamed(s.getLabel(), request.getHeader(HttpHeaders.USER_AGENT));
        if (better != null) s.setLabel(better);     // the app now says which phone it is
        s.setLastSeenAt(now);
        s.setRememberedUntil(rememberedUntil);
        signIns.save(s);
    }

    /** "Sign out" on this device. */
    @Transactional
    public void signedOut(String email, HttpServletRequest request) {
        String key = DeviceService.keyOf(request);
        if (email == null || key == null) return;
        users.findByEmailIgnoreCase(email)
                .flatMap(u -> signIns.findByUserAndDeviceKey(u, key))
                .ifPresent(signIns::delete);
    }

    /** How many browsers and phones the account is signed in on, this one included. */
    @Transactional
    public int count(User user) {
        return Math.max(1, signedIn(user).size());
    }

    /** One row on the "Signed-in devices" page. */
    public record Device(Long id, String label, LocalDateTime signedInAt, boolean current) {
        public boolean phone() {
            return label != null && (label.contains("Android") || label.contains("iPhone"));
        }
        public boolean tablet() {
            return label != null && label.contains("iPad");
        }
    }

    /** Where the account is signed in: this device first, then the most recently used. */
    @Transactional
    public List<Device> devices(User user, HttpServletRequest here) {
        String key = DeviceService.keyOf(here);
        List<Device> list = new ArrayList<>(signedIn(user).stream()
                .sorted(Comparator.comparing(SignIn::getLastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(s -> new Device(s.getId(), s.getLabel(), s.getSignedInAt(), s.getDeviceKey().equals(key)))
                .toList());
        list.sort(Comparator.comparing(d -> !d.current()));
        if (list.stream().noneMatch(Device::current)) {
            // This device's row is made on its first request after signing in, and may not exist yet
            list.add(0, new Device(null, DeviceService.describe(here.getHeader(HttpHeaders.USER_AGENT)), LocalDateTime.now(), true));
        }
        return list;
    }

    /** Signed in now; rows that ran out are cleared on the way. Devices signed out from elsewhere don't count. */
    private List<SignIn> signedIn(User user) {
        LocalDateTime now = LocalDateTime.now();
        List<SignIn> all = signIns.findByUser(user);
        List<SignIn> gone = all.stream().filter(s -> !active(s, now)).toList();
        signIns.deleteAll(gone);
        return all.stream().filter(s -> active(s, now) && s.getSignedOutAt() == null).toList();
    }

    /**
     * Signs the account out on one other device. Its next request is refused (SignInGuard); a phone also loses its
     * alarm sync at once. Returns the device's name, or empty when it's this device or not one of the account's.
     */
    @Transactional
    public Optional<String> endOne(User user, Long signInId, HttpServletRequest here) {
        String key = DeviceService.keyOf(here);
        Optional<SignIn> found = signIns.findByIdAndUser(signInId, user)
                .filter(s -> !s.getDeviceKey().equals(key) && s.getSignedOutAt() == null);
        found.ifPresent(s -> {
            s.setSignedOutAt(LocalDateTime.now());
            signIns.save(s);
            dropPhones(user, d -> d.getDeviceKey().equals(s.getDeviceKey()));
            trustedDevices.forget(user, s.getDeviceKey());    // signing in there again asks for the 2FA code
        });
        return found.map(SignIn::getLabel);
    }

    /** True when this device was signed out from another one. Clears its sign-in, so it's handled once. */
    @Transactional
    public boolean endedElsewhere(Long userId, HttpServletRequest request) {
        String key = DeviceService.keyOf(request);
        if (key == null || !signIns.existsByUser_IdAndDeviceKeyAndSignedOutAtIsNotNull(userId, key)) return false;
        signIns.findByUser_IdAndDeviceKey(userId, key).ifPresent(signIns::delete);
        return true;
    }

    /**
     * The admin account signs in on one device at a time. Refused (false) while another device is in use: it made a
     * request within the session timeout and hasn't signed out. Otherwise every other sign-in is ended, so a device
     * left signed in by a remember-me cookie (the app, "Keep me signed in") can't come back alongside this one.
     */
    @Transactional
    public boolean claimOnlySignIn(String email, HttpServletRequest request) {
        Optional<User> found = users.findByEmailIgnoreCase(email);
        if (found.isEmpty()) return true;
        User user = found.get();
        String key = DeviceService.keyOf(request);
        LocalDateTime since = LocalDateTime.now().minus(sessionTimeout).minus(SEEN_EVERY);
        boolean inUse = signIns.findByUser(user).stream()
                .anyMatch(s -> !s.getDeviceKey().equals(key) && s.getSignedOutAt() == null
                        && s.getLastSeenAt() != null && s.getLastSeenAt().isAfter(since));
        if (inUse) return false;
        end(user, key);
        return true;
    }

    /** A password sign-in on a device signed out from elsewhere earlier: it's signed in again. */
    @Transactional
    public void signedInAgain(User user, HttpServletRequest request) {
        String key = DeviceService.keyOf(request);
        if (key == null) return;
        signIns.findByUserAndDeviceKey(user, key).filter(s -> s.getSignedOutAt() != null).ifPresent(signIns::delete);
    }

    /**
     * Signs the account out everywhere except the device making this request, which stays signed in.
     * Returns how many other devices were signed out.
     */
    @Transactional
    public int endOthers(User user, HttpServletRequest here) {
        int n = end(user, DeviceService.keyOf(here));
        HttpSession session = here.getSession(false);
        if (session != null) session.setAttribute(EPOCH, currentEpoch(user));
        return n;
    }

    /** Signs the account out on every browser and phone. Returns how many there were. */
    @Transactional
    public int endAll(User user) {
        return end(user, null);
    }

    private int end(User user, String keepKey) {
        users.findById(user.getId()).ifPresent(u -> {
            u.setRememberEpoch(u.getRememberEpoch() + 1);
            users.save(u);
        });
        user.setRememberEpoch(currentEpoch(user));

        LocalDateTime now = LocalDateTime.now();
        List<SignIn> others = signIns.findByUser(user).stream()
                .filter(s -> !s.getDeviceKey().equals(keepKey)).toList();
        int n = (int) others.stream().filter(s -> active(s, now) && s.getSignedOutAt() == null).count();
        signIns.deleteAll(others);

        dropPhones(user, d -> !d.getDeviceKey().equals(keepKey));
        trustedDevices.forgetAllExcept(user, keepKey);    // their next sign-in asks for the 2FA code again
        return n;
    }

    /** Phones: their alarm sync token stops working; a push makes them sync now, get refused, and drop the alarms. */
    private void dropPhones(User user, Predicate<NotificationDevice> which) {
        List<String> wake = new ArrayList<>();
        for (NotificationDevice d : devices.findByUser(user)) {
            if (!which.test(d) || d.getAppTokenHash() == null) continue;
            if (d.getPushToken() != null) wake.add(d.getPushToken());
            d.setAppTokenHash(null);
            d.setAppLinkedAt(null);
            d.setPushToken(null);
            devices.save(d);
        }
        afterCommit(() -> wake.forEach(t -> push.send(t, Map.of("type", "SYNC"))));
    }

    private int currentEpoch(User user) {
        return users.findById(user.getId()).map(User::getRememberEpoch).orElse(user.getRememberEpoch());
    }

    /** Remembered and not yet expired, or used within the session timeout. */
    private boolean active(SignIn s, LocalDateTime now) {
        if (s.getRememberedUntil() != null && s.getRememberedUntil().isAfter(now)) return true;
        return s.getLastSeenAt() != null && s.getLastSeenAt().isAfter(now.minus(sessionTimeout).minus(SEEN_EVERY));
    }

    /** The phone must not sync before the token is really gone. */
    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    /** A password sign-in: the session belongs to this account now (it may have held another account's). */
    public static void started(HttpSession session, int accountEpoch) {
        session.setAttribute(EPOCH, accountEpoch);
        session.removeAttribute(SEEN);
    }

    /** True when a session signed in before the account's sign-ins were last ended. */
    public static boolean outdated(HttpSession session, int accountEpoch) {
        Object epoch = session.getAttribute(EPOCH);
        if (epoch == null) {
            session.setAttribute(EPOCH, accountEpoch);
            return false;
        }
        return !Objects.equals(epoch, accountEpoch);
    }
}
