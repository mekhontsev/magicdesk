package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AutomationDeviceStateTest {
    @Test public void requiresAwakeUnlockedPhoneAndShell() throws Exception {
        final var ready = new AutomationDeviceState(true, false, false);
        assertNull(ready.phoneUiUnavailableReason());
        assertTrue(ready.toJson(true).getBoolean("selfTestReady"));
        assertFalse(ready.toJson(false).getBoolean("selfTestReady"));
        assertEquals("check_shizuku", ready.toJson(false)
                .getJSONArray("requiredActions").getString(0));
        assertNotNull(new AutomationDeviceState(false, false, false).phoneUiUnavailableReason());
        assertNotNull(new AutomationDeviceState(true, true, false).phoneUiUnavailableReason());
        assertNotNull(new AutomationDeviceState(true, false, true).phoneUiUnavailableReason());
    }

    @Test public void unknownStateNeverBecomesReady() throws Exception {
        final var missing = new AutomationDeviceState(null, null, null);
        assertNotNull(missing.phoneUiUnavailableReason());
        assertFalse(missing.toJson(true).getBoolean("selfTestReady"));
        assertTrue(missing.toJson(true).isNull("interactive"));
    }
}
