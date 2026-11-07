package com.medizon.service;

import com.medizon.domain.Role;
import com.medizon.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Signing out changes the account and its phones through JPA; the deletion itself is plain SQL. The JPA changes must
 * be written before the SQL and nothing kept afterwards, or the commit writes them on top of it: an update of a phone
 * the SQL deleted (StaleObjectStateException), or the account's old name and email back over "Deleted customer".
 */
class AccountDeletionOrderTest {

    @Test
    void jpaChangesAreWrittenBeforeTheSqlAndDroppedAfter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any())).thenAnswer(i -> new ArrayList<String>());
        UserService users = mock(UserService.class);
        EntityManager em = mock(EntityManager.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("x");
        EmailService email = mock(EmailService.class);
        AccountDeletionService service = new AccountDeletionService(jdbc, users, mock(FileStorageService.class), encoder, em, email);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseUrl", "https://medizon.test");

        User u = new User();
        u.setId(9L);
        u.setRole(Role.CUSTOMER);
        u.setFullName("Asha Rao");
        u.setEmail("asha@example.com");
        when(users.checkPassword(u, "pw")).thenReturn(true);

        service.deleteOwn(u, "pw");

        InOrder order = inOrder(users, em, jdbc);
        order.verify(users).signOutEverywhere(u);
        order.verify(em).flush();
        order.verify(jdbc).update(startsWith("delete from notification_devices"), eq(9L));
        order.verify(jdbc).update(contains("update users set full_name"), any(Object[].class));
        order.verify(em).clear();

        // The customer is told, at the address the account had
        verify(email).sendTemplate(eq(EmailService.Sender.NOREPLY), eq("asha@example.com"), eq("Your MediZon account has been deleted"),
                eq("emails/account-deleted"), argThat(m -> "Asha".equals(m.get("firstName"))), contains("https://medizon.test/help"));
    }
}
