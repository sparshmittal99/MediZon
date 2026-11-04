package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.NotificationDeviceRepository;
import com.medizon.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Every notification reaches linked phones and email, except dose reminders: no email, and the app rings those itself. */
class PhoneNotificationTest {

    private EmailService email;
    private PushService phones;
    private NotificationService service;
    private final User customer = new User();

    @BeforeEach
    void setUp() {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.idsNewestFirst(any(), any())).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(i -> { Notification n = i.getArgument(0); n.setId(42L); return n; });
        email = mock(EmailService.class);
        phones = mock(PushService.class);
        when(phones.isEnabled()).thenReturn(true);
        NotificationDeviceRepository devices = mock(NotificationDeviceRepository.class);
        NotificationDevice phone = new NotificationDevice();
        phone.setPushToken("token-1");
        when(devices.findByUserAndPushTokenIsNotNull(any())).thenReturn(List.of(phone));
        service = new NotificationService(repo, email, mock(NotificationPush.class), phones, devices);
        ReflectionTestUtils.setField(service, "baseUrl", "https://medizon.test");
        customer.setRole(Role.CUSTOMER);
        customer.setEmail("a@b.c");
        customer.setFullName("Asha Rao");
    }

    @Test
    void doseRemindersAreNotEmailedOrPushed() {
        service.notify(customer, NotificationType.DOSE_REMINDER, "Time for Metformin", "Take 1 tablet", "/plans");
        verify(email, never()).sendTemplate(any(), any(), any(), any(), any());
        verify(phones, never()).send(any(), any());
    }

    @Test
    void otherNotificationsAreEmailedAndPushed() {
        service.notify(customer, NotificationType.PRESCRIPTION_UPDATE, "Prescription approved", "Its medicines are in your cart.", "/cart");
        verify(email).sendTemplate(eq("a@b.c"), contains("Prescription approved"), eq("emails/notification"), any(), any());
        verify(phones).send(eq("token-1"), eq(Map.of("nid", "42", "type", "PRESCRIPTION_UPDATE",
                "title", "Prescription approved", "body", "Its medicines are in your cart.", "link", "/cart")));
    }

    @Test
    void privateKeysParseFromTheServiceAccountFormat() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(g.generateKeyPair().getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
        org.assertj.core.api.Assertions.assertThat(PushService.privateKey(pem)).isNotNull();
        org.assertj.core.api.Assertions.assertThat(PushService.privateKey(pem.replace("\n", "\\n"))).isNotNull();   // escaped in env vars
    }
}
