package com.medizon.service;

import com.medizon.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.medizon.service.EmailService.Sender.*;
import static org.assertj.core.api.Assertions.assertThat;

class EmailSenderTest {

    @Test
    void eachKindOfEmailHasItsOwnSender() {
        assertThat(NotificationService.senderFor(NotificationType.ORDER_UPDATE, "Payment received")).isEqualTo(PAYMENTS);
        assertThat(NotificationService.senderFor(NotificationType.ORDER_UPDATE, "Paid with MediZon Money")).isEqualTo(PAYMENTS);
        assertThat(NotificationService.senderFor(NotificationType.ORDER_UPDATE, "Payment refunded")).isEqualTo(REFUNDS);
        assertThat(NotificationService.senderFor(NotificationType.ORDER_UPDATE, "Order cancelled")).isEqualTo(STORE);
        assertThat(NotificationService.senderFor(NotificationType.RETURN_UPDATE, "Return approved")).isEqualTo(RETURNS);
        assertThat(NotificationService.senderFor(NotificationType.RETURN_UPDATE, "Refund processed")).isEqualTo(REFUNDS);
        assertThat(NotificationService.senderFor(NotificationType.RETURN_UPDATE, "Refund added to MediZon Money")).isEqualTo(REFUNDS);
        assertThat(NotificationService.senderFor(NotificationType.SUPPORT_UPDATE, "New reply on T1")).isEqualTo(SUPPORT);
        assertThat(NotificationService.senderFor(NotificationType.PRESCRIPTION_UPDATE, "Prescription used up")).isEqualTo(NOREPLY);
        assertThat(NotificationService.senderFor(NotificationType.INFO, null)).isEqualTo(NOREPLY);
    }

    @Test
    void withADomainEachSenderHasItsOwnAddress() {
        EmailService mail = new EmailService(null, null);
        ReflectionTestUtils.setField(mail, "from", "MediZon <someone@gmail.com>");
        ReflectionTestUtils.setField(mail, "mailDomain", "medizon.dpdns.org");
        assertThat(mail.fromFor(STORE)).isEqualTo("MediZon Store <store@medizon.dpdns.org>");
        assertThat(mail.fromFor(RETURNS)).isEqualTo("MediZon Returns <return@medizon.dpdns.org>");
        assertThat(mail.fromFor(REFUNDS)).isEqualTo("MediZon Refunds <refund@medizon.dpdns.org>");
        assertThat(mail.fromFor(PAYMENTS)).isEqualTo("MediZon Payments <payments@medizon.dpdns.org>");
        assertThat(mail.fromFor(SUPPORT)).isEqualTo("MediZon Support <support@medizon.dpdns.org>");
        assertThat(mail.fromFor(NOREPLY)).isEqualTo("MediZon <noreply@medizon.dpdns.org>");
    }

    @Test
    void withoutADomainEverythingComesFromMailFrom() {
        EmailService mail = new EmailService(null, null);
        ReflectionTestUtils.setField(mail, "from", "MediZon <someone@gmail.com>");
        ReflectionTestUtils.setField(mail, "mailDomain", "");
        for (EmailService.Sender s : EmailService.Sender.values()) {
            assertThat(mail.fromFor(s)).isEqualTo("MediZon <someone@gmail.com>");
        }
    }
}
