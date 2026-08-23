package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellPhoneLauncherCircuitBreakerTest {
    @Test
    public void isolatesPrimaryHomeAfterFirstSessionCrash() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(true);
        breaker.configure(true);

        assertTrue(breaker.noteLauncherFailure(PhoneLauncherEvent.CRASH));
        assertTrue(breaker.isTripped());
        assertFalse(breaker.allowActivityStart(true));
        assertTrue(breaker.allowActivityStart(false));
        assertFalse(breaker.noteLauncherFailure(PhoneLauncherEvent.CRASH));
    }

    @Test
    public void ignoresAnrAndFailuresOutsideSession() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(true);

        assertFalse(breaker.noteLauncherFailure(PhoneLauncherEvent.CRASH));
        breaker.configure(true);
        assertFalse(breaker.noteLauncherFailure(PhoneLauncherEvent.ANR));
        assertTrue(breaker.allowActivityStart(true));
    }

    @Test
    public void sessionEndRestoresHomeAndResetsProtection() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(true);
        breaker.configure(true);
        breaker.noteLauncherFailure(PhoneLauncherEvent.CRASH);

        breaker.configure(false);

        assertFalse(breaker.isTripped());
        assertTrue(breaker.allowActivityStart(true));
        breaker.configure(true);
        assertTrue(breaker.allowActivityStart(true));
    }

    @Test
    public void disabledPlatformNeverBlocksHome() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(false);
        breaker.configure(true);

        assertFalse(breaker.noteLauncherFailure(PhoneLauncherEvent.CRASH));
        assertTrue(breaker.allowActivityStart(true));
    }
}
