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
}
