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
        verify(email, never()).sendTemplate(any(), any(), any(), any(), any(), any());
        verify(phones, never()).send(any(), any());
    }

    @Test
    void otherNotificationsAreEmailedAndPushed() {
        service.notify(customer, NotificationType.PRESCRIPTION_UPDATE, "Prescription approved", "Its medicines are in your cart.", "/cart");
        verify(email).sendTemplate(eq(EmailService.Sender.NOREPLY), eq("a@b.c"), contains("Prescription approved"), eq("emails/notification"), any(), any());
        verify(phones).send(eq("token-1"), eq(Map.of("nid", "42", "type", "PRESCRIPTION_UPDATE",
                "title", "Prescription approved", "body", "Its medicines are in your cart.", "link", "/cart")));
    }

    @Test
    void aCancelledOrdersRefundIsEmailedFromTheRefundsAddress() {
        // The status alert is covered by the order's own email; the refund alert is emailed on its own
        service.notify(customer, NotificationType.ORDER_UPDATE, "Order cancelled", "Order MZ1 is now cancelled.", "/orders/x");
        verify(email, never()).sendTemplate(any(), any(), any(), any(), any(), any());
        service.notify(customer, NotificationType.ORDER_UPDATE, "Refund initiated", "Rs 120 is going back to your UPI.", "/orders/x");
        verify(email).sendTemplate(eq(EmailService.Sender.REFUNDS), eq("a@b.c"), contains("Refund initiated"), eq("emails/notification"), any(), any());
    }

    @Test
    void refundMessageSaysHowMuchWentWhere() {
        Order o = new Order();
        o.setOrderNumber("MZ1001");
        o.setPaymentMethod(PaymentMethod.UPI);
        o.setGatewayRefundId("rfnd_9");
        String both = OrderService.refundMessage(o, new java.math.BigDecimal("120.00"), new java.math.BigDecimal("30.00"));
        org.assertj.core.api.Assertions.assertThat(both)
                .startsWith("₹120.00 refunded to your " + PaymentMethod.UPI.getLabel() + " for order MZ1001")
                .doesNotContain("through")
                .contains("for order MZ1001").contains("(refund id rfnd_9)").contains("5-7 working days")
                .contains("₹30.00 is back in your MediZon Money");
        String walletOnly = OrderService.refundMessage(o, java.math.BigDecimal.ZERO, new java.math.BigDecimal("30.00"));
        org.assertj.core.api.Assertions.assertThat(walletOnly)
                .isEqualTo("₹30.00 is back in your MediZon Money for order MZ1001, ready to use now.");
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
