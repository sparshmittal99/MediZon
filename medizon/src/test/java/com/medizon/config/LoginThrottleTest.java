package com.medizon.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    /** A clock the test moves forward by hand. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-16T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void anAccountLocksAfterFiveWrongPasswordsAndUnlocksAfterTheWindow() {
        TestClock clock = new TestClock();
        LoginThrottle t = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.MAX_PER_ACCOUNT - 1; i++) t.failed("Asha@Example.com", "1.1.1." + i);
        assertThat(t.isBlocked("asha@example.com", "9.9.9.9")).isFalse();

        t.failed("asha@example.com ", "1.1.1.9");
        assertThat(t.isBlocked("ASHA@example.com", "9.9.9.9")).isTrue();     // any address, any spelling of the email
        assertThat(t.isBlocked("ravi@example.com", "9.9.9.9")).isFalse();    // other accounts aren't affected

        clock.now = clock.now.plus(LoginThrottle.WINDOW).plusSeconds(1);
        assertThat(t.isBlocked("asha@example.com", "9.9.9.9")).isFalse();
    }

    @Test
    void oneAddressGuessingManyAccountsIsBlocked() {
        LoginThrottle t = new LoginThrottle(new TestClock());
        for (int i = 0; i < LoginThrottle.MAX_PER_IP; i++) t.failed("user" + i + "@example.com", "6.6.6.6");
        assertThat(t.isBlocked("someone-new@example.com", "6.6.6.6")).isTrue();
        assertThat(t.isBlocked("someone-new@example.com", "7.7.7.7")).isFalse();
    }

    @Test
    void aCorrectPasswordClearsTheAccountsCount() {
        LoginThrottle t = new LoginThrottle(new TestClock());
        for (int i = 0; i < LoginThrottle.MAX_PER_ACCOUNT - 1; i++) t.failed("asha@example.com", "1.1.1.1");
        t.succeeded("asha@example.com");
        t.failed("asha@example.com", "1.1.1.1");
        assertThat(t.isBlocked("asha@example.com", "2.2.2.2")).isFalse();
    }

    @Test
    void cleanUpForgetsOldFailures() {
        TestClock clock = new TestClock();
        LoginThrottle t = new LoginThrottle(clock);
        t.failed("asha@example.com", "1.1.1.1");
        clock.now = clock.now.plus(Duration.ofHours(1));
        t.cleanUp();
        for (int i = 0; i < LoginThrottle.MAX_PER_ACCOUNT - 1; i++) t.failed("asha@example.com", "1.1.1.1");
        assertThat(t.isBlocked("asha@example.com", "2.2.2.2")).isFalse();
    }
}
