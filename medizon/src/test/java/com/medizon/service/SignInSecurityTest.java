package com.medizon.service;

import com.medizon.domain.KnownDevice;
import com.medizon.domain.NotificationDevice;
import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.repository.KnownDeviceRepository;
import com.medizon.repository.NotificationDeviceRepository;
import com.medizon.repository.SignInRepository;
import com.medizon.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Sign in with Google's account matching, and the new-device sign-in email. */
class SignInSecurityTest {

    @Nested
    class Google {
        private UserRepository users;
        private UserService userService;
        private GoogleAccountService service;

        @BeforeEach
        void setUp() {
            users = mock(UserRepository.class);
            userService = mock(UserService.class);
            service = new GoogleAccountService(users, userService);
            when(users.findByGoogleSubject(anyString())).thenReturn(Optional.empty());
            when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        }

        private User account(String email, Role role) {
            User u = new User();
            u.setId(1L);
            u.setEmail(email);
            u.setRole(role);
            when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(u));
            return u;
        }

        @Test
        void aNewEmailMakesACustomerAccountWithGooglesName() {
            User made = new User();
            when(userService.registerWithGoogle("Asha Rao", "asha@example.com", "g-1")).thenReturn(made);
            GoogleAccountService.Result r = service.resolve("g-1", "Asha@Example.com", true, "Asha Rao");
            assertThat(r.created()).isTrue();
            assertThat(r.user()).isSameAs(made);
        }

        @Test
        void anExistingCustomerWithTheSameEmailIsLinked() {
            User asha = account("asha@example.com", Role.CUSTOMER);
            GoogleAccountService.Result r = service.resolve("g-1", "asha@example.com", true, "Asha");
            assertThat(r.created()).isFalse();
            assertThat(r.user()).isSameAs(asha);
            assertThat(asha.getGoogleSubject()).isEqualTo("g-1");
            verify(userService, never()).registerWithGoogle(any(), any(), any());
        }

        @Test
        void staffDisabledAndUnverifiedAreRefused() {
            account("pharma@medizon.in", Role.PHARMACIST);
            GoogleAccountService.Refused staff = assertThrows(GoogleAccountService.Refused.class,
                    () -> service.resolve("g-2", "pharma@medizon.in", true, "P"));
            assertThat(staff.reason()).isEqualTo(GoogleAccountService.Refusal.STAFF);

            User off = account("off@example.com", Role.CUSTOMER);
            off.setEnabled(false);
            assertThat(assertThrows(GoogleAccountService.Refused.class, () -> service.resolve("g-3", "off@example.com", true, "O"))
                    .reason()).isEqualTo(GoogleAccountService.Refusal.DISABLED);

            assertThat(assertThrows(GoogleAccountService.Refused.class, () -> service.resolve("g-4", "new@example.com", false, "N"))
                    .reason()).isEqualTo(GoogleAccountService.Refusal.UNVERIFIED);
        }
    }

    @Nested
    class NewDevice {
        private static final String LAPTOP = "11111111-1111-1111-1111-111111111111";
        private static final String PHONE = "22222222-2222-2222-2222-222222222222";

        private KnownDeviceRepository known;
        private NotificationDeviceRepository notificationDevices;
        private SecureAccountService secure;
        private NewDeviceMailer mailer;
        private NewDeviceAlertService service;
        private User user;

        @BeforeEach
        void setUp() {
            known = mock(KnownDeviceRepository.class);
            SignInRepository signIns = mock(SignInRepository.class);
            notificationDevices = mock(NotificationDeviceRepository.class);
            UserRepository users = mock(UserRepository.class);
            secure = mock(SecureAccountService.class);
            mailer = mock(NewDeviceMailer.class);
            service = new NewDeviceAlertService(known, signIns, notificationDevices, users, secure, mailer);
            user = new User();
            user.setId(9L);
            user.setEmail("asha@example.com");
            user.setFullName("Asha Rao");
            when(users.getReferenceById(9L)).thenReturn(user);
            when(secure.issue(any(), any())).thenReturn("token");
        }

        private MockHttpServletRequest from(String key) {
            MockHttpServletRequest r = new MockHttpServletRequest("POST", "/login");
            r.setCookies(new Cookie(DeviceService.COOKIE, key));
            r.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0 Safari/537.36");
            return r;
        }

        @Test
        void aDeviceSignedInOnBeforeSendsNothing() {
            when(known.existsByUserAndDeviceKey(user, LAPTOP)).thenReturn(true);
            service.signedIn(user, from(LAPTOP), new MockHttpServletResponse(), false);
            verifyNoInteractions(mailer);
        }

        @Test
        void aNewDeviceSendsTheEmailWithItsName() {
            when(known.existsByUser(user)).thenReturn(true);
            service.signedIn(user, from(PHONE), new MockHttpServletResponse(), false);
            verify(known).save(any(KnownDevice.class));
            verify(mailer).send(argThat(a -> a.device().equals("Chrome on Windows") && a.to().equals("asha@example.com")
                    && a.secureToken().equals("token")));
        }

        @Test
        void theFirstSignInEverAndANewAccountSendNothing() {
            service.signedIn(user, from(LAPTOP), new MockHttpServletResponse(), false);   // no devices before
            service.signedIn(user, from(PHONE), new MockHttpServletResponse(), true);     // just made with Google
            verifyNoInteractions(mailer);
        }

        @Test
        void devicesUsedBeforeThisFeatureCountAsKnown() {
            NotificationDevice old = new NotificationDevice();
            old.setDeviceKey(LAPTOP);
            when(notificationDevices.findByUser(user)).thenReturn(List.of(old));
            service.signedIn(user, from(LAPTOP), new MockHttpServletResponse(), false);
            verifyNoInteractions(mailer);
        }
    }

    @Test
    void onlyRealInternetAddressesAreLookedUp() {
        assertThat(GeoIpClient.isPublic("8.8.8.8")).isTrue();
        assertThat(GeoIpClient.isPublic("2001:4860:4860::8888")).isTrue();
        for (String local : new String[]{"127.0.0.1", "10.2.3.4", "192.168.1.5", "172.16.0.9", "100.64.1.1", "::1",
                "fd00::1", "localhost", "", null}) {
            assertThat(GeoIpClient.isPublic(local)).as(String.valueOf(local)).isFalse();
        }
    }
}
