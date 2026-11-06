package com.medizon.service;

import com.medizon.domain.NotificationDevice;
import com.medizon.domain.SignIn;
import com.medizon.domain.User;
import com.medizon.repository.NotificationDeviceRepository;
import com.medizon.repository.SignInRepository;
import com.medizon.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Account > Signed-in devices: signing out one device from another. */
class DeviceSignOutTest {

    private static final String HERE = "11111111-1111-1111-1111-111111111111";
    private static final String PHONE = "22222222-2222-2222-2222-222222222222";

    private SignInRepository signIns;
    private UserRepository users;
    private NotificationDeviceRepository devices;
    private PushService push;
    private SignInService service;
    private User user;
    private SignIn here, phone;

    @BeforeEach
    void setUp() {
        signIns = mock(SignInRepository.class);
        devices = mock(NotificationDeviceRepository.class);
        push = mock(PushService.class);
        users = mock(UserRepository.class);
        service = new SignInService(signIns, users, devices, push, Duration.ofMinutes(30));
        user = new User();
        user.setId(7L);
        when(users.findByEmailIgnoreCase("admin@medizon.in")).thenReturn(Optional.of(user));
        here = signIn(1L, HERE, "Chrome on Windows");
        phone = signIn(2L, PHONE, "MediZon app on Android");
        phone.setRememberedUntil(LocalDateTime.now().plusYears(5));
        when(signIns.findByUser(user)).thenReturn(List.of(phone, here));
        when(signIns.findByIdAndUser(1L, user)).thenReturn(Optional.of(here));
        when(signIns.findByIdAndUser(2L, user)).thenReturn(Optional.of(phone));
    }

    private SignIn signIn(Long id, String key, String label) {
        SignIn s = new SignIn();
        s.setId(id);
        s.setUser(user);
        s.setDeviceKey(key);
        s.setLabel(label);
        return s;
    }

    private static MockHttpServletRequest from(String key) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setCookies(new Cookie(DeviceService.COOKIE, key));
        return r;
    }

    @Test
    void listsThisDeviceFirstAndMarksIt() {
        List<SignInService.Device> list = service.devices(user, from(HERE));
        assertThat(list).extracting(SignInService.Device::label).containsExactly("Chrome on Windows", "MediZon app on Android");
        assertThat(list.get(0).current()).isTrue();
        assertThat(list.get(1).current()).isFalse();
        assertThat(list.get(1).phone()).isTrue();
    }

    @Test
    void signsOutOneOtherDeviceAndDropsItsAlarms() {
        NotificationDevice app = new NotificationDevice();
        app.setDeviceKey(PHONE);
        app.setAppTokenHash("hash");
        app.setPushToken("fcm");
        when(devices.findByUser(user)).thenReturn(List.of(app));

        assertThat(service.endOne(user, 2L, from(HERE))).contains("MediZon app on Android");
        assertThat(phone.getSignedOutAt()).isNotNull();
        assertThat(app.getAppTokenHash()).isNull();
        verify(push).send(eq("fcm"), anyMap());
        // It no longer shows, or counts
        assertThat(service.devices(user, from(HERE))).hasSize(1);
        assertThat(service.count(user)).isEqualTo(1);
    }

    @Test
    void cantSignOutThisDeviceFromTheList() {
        assertThat(service.endOne(user, 1L, from(HERE))).isEmpty();
        assertThat(here.getSignedOutAt()).isNull();
    }

    @Test
    void theSignedOutDeviceIsSignedOutOnItsNextRequestOnce() {
        when(signIns.existsByUser_IdAndDeviceKeyAndSignedOutAtIsNotNull(7L, PHONE)).thenReturn(true);
        when(signIns.findByUser_IdAndDeviceKey(7L, PHONE)).thenReturn(Optional.of(phone));
        assertThat(service.endedElsewhere(7L, from(PHONE))).isTrue();
        verify(signIns).delete(phone);
        assertThat(service.endedElsewhere(7L, from(HERE))).isFalse();
    }

    // ---- the admin account: one device at a time

    @Test
    void adminCantSignInWhileAnotherDeviceIsInUse() {
        MockHttpServletRequest newDevice = new MockHttpServletRequest();    // never signed in here: no device cookie
        assertThat(service.claimOnlySignIn("admin@medizon.in", newDevice)).isFalse();
        assertThat(service.claimOnlySignIn("admin@medizon.in", from(PHONE))).isFalse();
        verify(signIns, never()).deleteAll(anyList());
    }

    @Test
    void adminCanSignInAgainOnTheDeviceAlreadyInUse() {
        when(signIns.findByUser(user)).thenReturn(List.of(here));
        assertThat(service.claimOnlySignIn("admin@medizon.in", from(HERE))).isTrue();
    }

    @Test
    void adminCanSignInOnceTheOtherDeviceIsIdleAndThatOneIsSignedOut() {
        phone.setLastSeenAt(LocalDateTime.now().minusHours(2));    // still remembered by the app, but not in use
        when(signIns.findByUser(user)).thenReturn(List.of(phone));
        assertThat(service.claimOnlySignIn("admin@medizon.in", new MockHttpServletRequest())).isTrue();
        verify(signIns).deleteAll(List.of(phone));
    }

    @Test
    void adminCanSignInAfterTheOtherDeviceWasSignedOutFromTheList() {
        phone.setSignedOutAt(LocalDateTime.now());
        when(signIns.findByUser(user)).thenReturn(List.of(phone));
        assertThat(service.claimOnlySignIn("admin@medizon.in", from(HERE))).isTrue();
    }
}
