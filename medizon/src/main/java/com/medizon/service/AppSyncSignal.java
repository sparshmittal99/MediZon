package com.medizon.service;

import com.medizon.domain.NotificationDevice;
import com.medizon.domain.User;
import com.medizon.repository.NotificationDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

/**
 * Tells the user's Android phones to fetch their dose alarms now (a silent Firebase "SYNC" message), whenever a
 * medicine's schedule or a one-off reminder changes. Without it a new dose time only reached the phone at its next
 * periodic sync, which could be after the dose.
 */
@Service
@RequiredArgsConstructor
public class AppSyncSignal {

    private final PushService push;
    private final NotificationDeviceRepository devices;

    public void scheduleChanged(User user) {
        if (user == null || user.getId() == null || !push.isEnabled()) return;
        List<String> tokens = devices.findByUserAndPushTokenIsNotNull(user).stream()
                .map(NotificationDevice::getPushToken).toList();
        if (tokens.isEmpty()) return;
        Runnable send = () -> tokens.forEach(t -> push.send(t, Map.of("type", "SYNC")));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }
}
