package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CompatibilityDiagnosticsTest {
    @Test
    public void removesStaticAuditStatesFromHistoricalEvents() {
        final String events =
                "2026-08-04 | PROFILE-001 | Unverified firmware\n"
                        + "2026-08-04 | SHIZUKU-001 | Server stopped\n"
                        + "2026-08-04 | SHELL-CONSOLE-002 | Launch failed\n"
                        + "2026-08-04 | PLATFORM-001 | Unsupported platform\n"
                        + "2026-08-04 | NUBIA-SCREEN-002 | Screen failed\n";

        assertEquals(
                "2026-08-04 | SHELL-CONSOLE-002 | Launch failed\n"
                        + "2026-08-04 | NUBIA-SCREEN-002 | Screen failed\n",
                CompatibilityDiagnostics.filterStaticAuditEvents(events));
    }
}
