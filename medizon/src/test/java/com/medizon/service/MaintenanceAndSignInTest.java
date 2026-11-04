package com.medizon.service;

import com.medizon.config.MediZonRememberMeServices;
import com.medizon.domain.*;
import com.medizon.repository.MaintenanceWindowRepository;
import com.medizon.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Scheduled downtime announcements, and the Android app staying signed in. */
class MaintenanceAndSignInTest {

    private static MaintenanceWindow window(LocalDateTime start, LocalDateTime end) {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setStartsAt(start);
        w.setEndsAt(end);
        return w;
    }

    @Test
    void downtimeReadsShort() {
        LocalDateTime day = LocalDateTime.of(2026, 11, 12, 2, 0);
        assertThat(MaintenanceService.when(window(day, day.plusHours(2))))
                .isEqualTo("on Thu 12 Nov, 2:00 AM – 4:00 AM");
        assertThat(MaintenanceService.when(window(day.withHour(23), day.plusDays(1).withHour(1))))
                .isEqualTo("from Thu 12 Nov, 11:00 PM to Fri 13 Nov, 1:00 AM");
    }

    @Test
    void announcingTellsEveryCustomer() {
        MaintenanceWindowRepository windows = mock(MaintenanceWindowRepository.class);
        UserRepository users = mock(UserRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        User a = new User(), b = new User();
        when(users.findByRoleAndDeletedFalseAndEnabledTrue(Role.CUSTOMER)).thenReturn(List.of(a, b));
        MaintenanceService service = new MaintenanceService(windows, users, notifications);

        LocalDateTime start = LocalDateTime.now().plusDays(1);
        service.schedule(start, start.plusHours(2));

        verify(notifications, times(2)).notify(any(), eq(NotificationType.MAINTENANCE), eq("Scheduled downtime"),
                argThat(m -> m.startsWith("MediZon will be down for maintenance on ") && m.endsWith("may not work during this time.")), isNull());
    }

    @Test
    void downtimeMustMakeSense() {
        MaintenanceService service = new MaintenanceService(mock(MaintenanceWindowRepository.class), mock(UserRepository.class),
                mock(NotificationService.class));
        LocalDateTime now = LocalDateTime.now();
        assertThrows(BusinessException.class, () -> service.schedule(now.minusHours(1), now.plusHours(1)));   // started
        assertThrows(BusinessException.class, () -> service.schedule(now.plusHours(2), now.plusHours(1)));   // ends first
        assertThrows(BusinessException.class, () -> service.schedule(now.plusHours(1), now.plusDays(3)));    // too long
    }

    private static MediZonRememberMeServices rememberMe(int epoch) {
        UserRepository users = mock(UserRepository.class);
        User u = new User();
        u.setRememberEpoch(epoch);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(u));
        UserDetails details = org.springframework.security.core.userdetails.User.withUsername("a@b.c").password("{noop}x").roles("CUSTOMER").build();
        UserDetailsService uds = name -> details;
        return new MediZonRememberMeServices("key", uds, users);
    }

    private static MockHttpServletResponse signIn(MediZonRememberMeServices rm, String userAgent, boolean ticked) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.addHeader("User-Agent", userAgent);
        if (ticked) req.setParameter("remember-me", "on");
        MockHttpServletResponse res = new MockHttpServletResponse();
        UserDetails d = org.springframework.security.core.userdetails.User.withUsername("a@b.c").password("{noop}x").roles("CUSTOMER").build();
        rm.loginSuccess(req, res, UsernamePasswordAuthenticationToken.authenticated(d, null, d.getAuthorities()));
        return res;
    }

    @Test
    void theAppIsAlwaysRememberedForYears() {
        MockHttpServletResponse res = signIn(rememberMe(0), "Mozilla/5.0 (Linux; Android 14) MediZonApp/1.0", false);
        assertThat(res.getCookie("remember-me")).isNotNull();
        assertThat(res.getCookie("remember-me").getMaxAge()).isGreaterThan(5 * 365 * 24 * 3600);
    }

    @Test
    void theWebsiteRemembersOnlyWhenTickedFor14Days() {
        assertThat(signIn(rememberMe(0), "Mozilla/5.0 Chrome/120", false).getCookie("remember-me")).isNull();
        MockHttpServletResponse res = signIn(rememberMe(0), "Mozilla/5.0 Chrome/120", true);
        assertThat(res.getCookie("remember-me").getMaxAge()).isEqualTo(14 * 24 * 3600);
    }

    @Test
    void signingOutOtherDevicesEndsRememberedSignIns() {
        String cookie = signIn(rememberMe(0), "Mozilla/5.0 MediZonApp/1.0", false).getCookie("remember-me").getValue();

        MockHttpServletRequest later = new MockHttpServletRequest("GET", "/");
        later.setCookies(new jakarta.servlet.http.Cookie("remember-me", cookie));
        assertThat(rememberMe(0).autoLogin(later, new MockHttpServletResponse())).isNotNull();       // same epoch: still in
        assertThat(rememberMe(1).autoLogin(later, new MockHttpServletResponse())).isNull();          // bumped: signed out
    }
}
