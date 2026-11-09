package com.medizon.service;

import com.medizon.domain.KnownDevice;
import com.medizon.domain.NotificationDevice;
import com.medizon.domain.SignIn;
import com.medizon.domain.User;
import com.medizon.repository.KnownDeviceRepository;
import com.medizon.repository.NotificationDeviceRepository;
import com.medizon.repository.SignInRepository;
import com.medizon.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * "New sign-in to your account" emails, the way Google sends them: only the first time an account signs in on a
 * browser or phone. Signing in again there (after signing out, or when the session ran out) sends nothing.
 * <p>
 * A device is its mz_device cookie, so a browser whose cookies were cleared, a private window or a reinstalled app
 * counts as new. An account's first sign-in ever (just created) sends nothing either; the welcome email covers it.
 */
@Service
@RequiredArgsConstructor
public class NewDeviceAlertService {

    private final KnownDeviceRepository known;
    private final SignInRepository signIns;
    private final NotificationDeviceRepository notificationDevices;
    private final UserRepository users;
    private final SecureAccountService secureAccount;
    private final NewDeviceMailer mailer;

    @Transactional
    public void signedIn(User user, HttpServletRequest request, HttpServletResponse response, boolean newAccount) {
        String key = DeviceService.key(request, response);
        User u = users.getReferenceById(user.getId());
        if (known.existsByUserAndDeviceKey(u, key)) return;

        boolean hadDevices = known.existsByUser(u);
        if (!hadDevices) {
            // The first sign-in since this feature came in: the devices the account already used aren't new
            Set<String> before = new LinkedHashSet<>();
            signIns.findByUser(u).stream().map(SignIn::getDeviceKey).forEach(before::add);
            notificationDevices.findByUser(u).stream().map(NotificationDevice::getDeviceKey).forEach(before::add);
            before.forEach(k -> known.save(new KnownDevice(u, k)));
            if (before.contains(key)) return;
            hadDevices = !before.isEmpty();
        }
        known.save(new KnownDevice(u, key));
        if (newAccount || !hadDevices) return;

        String device = DeviceService.describe(request.getHeader(HttpHeaders.USER_AGENT));
        String token = secureAccount.issue(u, device);
        NewDeviceMailer.Alert alert = new NewDeviceMailer.Alert(user.getEmail(), user.getFirstName(), device,
                LocalDateTime.now(), GeoIpClient.clientIp(request), token);
        afterCommit(() -> mailer.send(alert));
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
