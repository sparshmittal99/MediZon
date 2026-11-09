package com.medizon.config;

import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import com.medizon.service.SignInService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The sign-in check itself: the admin account on one device at a time, everyone else as before. */
class AdminSingleSignInTest {

    private final SecurityConfig config = new SecurityConfig();
    private final PasswordEncoder encoder = config.passwordEncoder();
    private final UserRepository users = mock(UserRepository.class);
    private final SignInService signIns = mock(SignInService.class);
    private final SignInFlow flow = mock(SignInFlow.class);
    private MockHttpServletRequest request;
    private DaoAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        account("admin@medizon.in", Role.ADMIN);
        account("asha@example.com", Role.CUSTOMER);
        provider = config.authenticationProvider(new AppUserDetailsService(users), encoder, signIns, flow);
        request = new MockHttpServletRequest("POST", "/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void account(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(encoder.encode("secret"));
        u.setRole(role);
        u.setEnabled(true);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(u));
    }

    private static UsernamePasswordAuthenticationToken signIn(String email, String password) {
        return UsernamePasswordAuthenticationToken.unauthenticated(email, password);
    }

    @Test
    void adminIsRefusedWhileSignedInElsewhere() {
        when(signIns.claimOnlySignIn(eq("admin@medizon.in"), any())).thenReturn(false);
        assertThrows(AdminInUseException.class, () -> provider.authenticate(signIn("admin@medizon.in", "secret")));
    }

    @Test
    void adminSignsInWhenNotInUseElsewhere() {
        when(signIns.claimOnlySignIn(eq("admin@medizon.in"), any())).thenReturn(true);
        assertThat(provider.authenticate(signIn("admin@medizon.in", "secret")).isAuthenticated()).isTrue();
    }

    @Test
    void aWrongPasswordIsJustAWrongPassword() {
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(signIn("admin@medizon.in", "nope")));
        verifyNoInteractions(signIns);    // whether the admin is signed in somewhere isn't given away
        verifyNoInteractions(flow);       // nor whether the account has 2FA on
    }

    @Test
    void customersCanSignInOnAnyNumberOfDevices() {
        assertThat(provider.authenticate(signIn("asha@example.com", "secret")).isAuthenticated()).isTrue();
        verifyNoInteractions(signIns);
    }

    @Test
    void theRightPasswordOnAnAccountWithTwoFactorWaitsForTheCode() {
        when(flow.needsCode(eq("asha@example.com"), any())).thenReturn(true);
        assertThat(provider.authenticate(signIn("asha@example.com", "secret")).isAuthenticated()).isTrue();
        // Marked, so no remember-me cookie is set and the success handler asks for the code instead of signing in
        assertThat(request.getAttribute(SignInFlow.NEEDS_CODE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void aTrustedDeviceOrNoTwoFactorSignsStraightIn() {
        when(flow.needsCode(eq("asha@example.com"), any())).thenReturn(false);
        provider.authenticate(signIn("asha@example.com", "secret"));
        assertThat(request.getAttribute(SignInFlow.NEEDS_CODE)).isNull();
    }
}
