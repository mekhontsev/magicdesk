package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public final class PhoneTaskNormalizationDiagnosticsTest {
    @Before
    public void reset() {
        PhoneTaskNormalizationDiagnostics.resetForTests();
    }

    @Test
    public void recordsLastNormalizedTask() {
        PhoneTaskNormalizationDiagnostics.noteNormalization(42);

        final PhoneTaskNormalizationDiagnostics.Snapshot snapshot =
                PhoneTaskNormalizationDiagnostics.snapshot();
        assertEquals(1, snapshot.normalizations);
        assertEquals(42, snapshot.lastTaskId);
        assertEquals(
                "normalizations=1, lastTask=42",
                snapshot.reportLine());
    }
}
