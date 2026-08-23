package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public final class PhoneTaskGuardDiagnosticsTest {
    @After
    public void reset() {
        PhoneTaskGuardDiagnostics.resetForTests();
    }

    @Test
    public void recordsSuccessfulPhoneTaskNormalization() {
        PhoneTaskGuardDiagnostics.noteNormalization(42);

        final PhoneTaskGuardDiagnostics.Snapshot snapshot =
                PhoneTaskGuardDiagnostics.snapshot();
        assertEquals(1, snapshot.normalizations);
        assertEquals(42, snapshot.lastTaskId);
    }

    @Test
    public void recordsLauncherFailureAndRecovery() {
        PhoneTaskGuardDiagnostics.noteLauncherEvent(
                PhoneLauncherEvent.CRASH, true);
        PhoneTaskGuardDiagnostics.noteLauncherEvent(
                PhoneLauncherEvent.HOME_START_BLOCKED, false);
        PhoneTaskGuardDiagnostics.noteLauncherEvent(
                PhoneLauncherEvent.HOME_START_ALLOWED, false);

        final PhoneTaskGuardDiagnostics.Snapshot snapshot =
                PhoneTaskGuardDiagnostics.snapshot();
        assertEquals(1, snapshot.launcherStarts);
        assertEquals(1, snapshot.launcherStartsBlocked);
        assertEquals(1, snapshot.launcherCrashes);
        assertEquals(0, snapshot.launcherAnrs);
        assertEquals(1, snapshot.launcherRecoveries);
        assertEquals(1, snapshot.launcherProtections);
    }
}
