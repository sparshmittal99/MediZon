package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.AnnouncementRepository;
import com.medizon.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Admin announcements go out by the channels the admin picked. */
class AnnouncementServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final EmailService email = mock(EmailService.class);
    private final AnnouncementService service = new AnnouncementService(mock(AnnouncementRepository.class), users, notifications, email);

    @BeforeEach
    void customers() {
        User a = new User(), b = new User();
        a.setEmail("a@example.com");
        b.setEmail("b@example.com");
        when(users.findByRoleAndDeletedFalseAndEnabledTrue(Role.CUSTOMER)).thenReturn(List.of(a, b));
        ReflectionTestUtils.setField(service, "baseUrl", "https://medizon.in");
    }

    @Test
    void bothChannelsNotifyAndEmail() {
        Announcement a = service.send(AnnouncementKind.SALE, "Weekend sale", "20% off", "save20", "/medicines", true, true, "admin");
        verify(notifications, times(2)).notify(any(), eq(NotificationType.ANNOUNCEMENT), eq("Weekend sale"),
                eq("20% off Use code SAVE20 at checkout."), eq("/medicines"), isNull(), eq(true));
        verifyNoInteractions(email);
        assertThat(a.getRecipients()).isEqualTo(2);
    }

    @Test
    void notificationOnlyDoesNotEmail() {
        service.send(AnnouncementKind.UPDATE, "New app", "Update now", null, null, false, true, "admin");
        verify(notifications, times(2)).notify(any(), eq(NotificationType.ANNOUNCEMENT), any(), any(), isNull(), isNull(), eq(false));
        verifyNoInteractions(email);
    }

    @Test
    void emailOnlySkipsNotifications() {
        service.send(AnnouncementKind.DISCOUNT, "Discount", "15% off", null, "/cart", true, false, "admin");
        verify(email, times(2)).sendTemplate(anyString(), eq("MediZon: Discount"), eq("emails/notification"), anyMap(), anyString());
        verifyNoInteractions(notifications);
    }

    @Test
    void needsAChannelAndALocalLink() {
        assertThrows(BusinessException.class, () -> service.send(AnnouncementKind.SALE, "T", "M", null, null, false, false, "x"));
        assertThrows(BusinessException.class, () -> service.send(AnnouncementKind.SALE, "T", "M", null, "https://evil.com", true, true, "x"));
        assertThrows(BusinessException.class, () -> service.send(AnnouncementKind.SALE, "T", "M", null, "//evil.com", true, true, "x"));
        assertThrows(BusinessException.class, () -> service.send(AnnouncementKind.SALE, " ", "M", null, null, true, true, "x"));
    }
}
