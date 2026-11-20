package com.medizon.config;

import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import com.medizon.service.SignInService;
import com.medizon.service.TrustedDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The admin's 2FA code is only for taking the account over from another device: never asked at a normal sign-in,
 * and accepted only while the account is in use elsewhere.
 */
class AdminTakeoverTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SignInService signIns = mock(SignInService.class);
    private final TrustedDeviceService trusted = mock(TrustedDeviceService.class);
    private final SignInFlow flow = new SignInFlow(users, mock(AppUserDetailsService.class), mock(MediZonRememberMeServices.class),
            mock(org.springframework.security.core.session.SessionRegistry.class), null, null, signIns,
            mock(LoginThrottle.class), trusted, null);
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("POST", "/login");
        response = new MockHttpServletResponse();
    }

    private User account(String email, Role role, boolean twoFactor) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        if (twoFactor) {
            u.setTwoFactorSecret("secret");
            u.setTwoFactorEnabledAt(LocalDateTime.now());
        }
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void adminNormalSignInNeverAsksForTheCode() {
        account("admin@medizon.in", Role.ADMIN, true);
        assertThat(flow.needsCode("admin@medizon.in", request)).isFalse();
    }

    @Test
    void customersWith2faStillGetAsked() {
        account("asha@example.com", Role.CUSTOMER, true);
        assertThat(flow.needsCode("asha@example.com", request)).isTrue();
    }

    @Test
    void inUseWith2faAsksForTheCodeToTakeOver() throws Exception {
        account("admin@medizon.in", Role.ADMIN, true);
        flow.afterAdminInUse(request, response, "admin@medizon.in");
        assertThat(response.getRedirectedUrl()).isEqualTo("/login/verify");
        SignInFlow.Pending p = flow.pending(request);
        assertThat(p).isNotNull();
        assertThat(p.takeover()).isTrue();
        verify(signIns, never()).endOthers(any(), any());     // nothing ended until the code is right
    }

    @Test
    void inUseWithout2faIsRefusedAsBefore() throws Exception {
        account("admin@medizon.in", Role.ADMIN, false);
        flow.afterAdminInUse(request, response, "admin@medizon.in");
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?inUse");
        assertThat(flow.pending(request)).isNull();
    }
}
