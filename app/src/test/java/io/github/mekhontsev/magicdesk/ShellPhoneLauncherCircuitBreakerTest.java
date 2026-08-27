package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellPhoneLauncherCircuitBreakerTest {
    @Test
    public void ignoresSignalsOtherThanObservedProcessDeath() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(true);
        breaker.configure(true);

        assertFalse(breaker.noteLauncherFailure(PhoneLauncherEvent.ANR));
        assertFalse(breaker.isTripped());
    }

    @Test
    public void ignoresAnrAndFailuresOutsideSession() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(true);

        assertFalse(breaker.noteLauncherFailure(
                PhoneLauncherEvent.PROCESS_DIED));
        breaker.configure(true);
        assertFalse(breaker.noteLauncherFailure(PhoneLauncherEvent.ANR));
        assertTrue(breaker.allowActivityStart(true));
    }

    @Test
    public void isolatesPrimaryHomeAfterForegroundProcessDeath() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(true);
        breaker.configure(true);

        assertTrue(breaker.noteLauncherFailure(
                PhoneLauncherEvent.PROCESS_DIED));
        assertFalse(breaker.allowActivityStart(true));
        assertFalse(breaker.allowActivityResume(true));
        assertTrue(breaker.allowActivityStart(false));
        assertTrue(breaker.allowActivityResume(false));
        assertFalse(breaker.noteLauncherFailure(
                PhoneLauncherEvent.PROCESS_DIED));
    }

    @Test
    public void sessionEndRestoresHomeAndResetsProtection() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(true);
        breaker.configure(true);
        breaker.noteLauncherFailure(PhoneLauncherEvent.PROCESS_DIED);

        breaker.configure(false);

        assertFalse(breaker.isTripped());
        assertTrue(breaker.allowActivityStart(true));
        assertTrue(breaker.allowActivityResume(true));
        breaker.configure(true);
        assertTrue(breaker.allowActivityStart(true));
    }

    @Test
    public void disabledPlatformNeverBlocksHome() {
        final ShellPhoneLauncherCircuitBreaker breaker =
                new ShellPhoneLauncherCircuitBreaker(false);
        breaker.configure(true);

        assertFalse(breaker.noteLauncherFailure(
                PhoneLauncherEvent.PROCESS_DIED));
        assertTrue(breaker.allowActivityStart(true));
        assertTrue(breaker.allowActivityResume(true));
    }
}
