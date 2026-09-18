package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AutomationDeviceStateTest {
    @Test public void disabledDesktopIsNotARequestForPrivilegeAuthorization() throws Exception {
        final var state = new AutomationDeviceState(true, false, false, 35,
                new RuntimeLimits.Values(RuntimeLimits.Access.APP_ONLY, true, true));
        assertFalse(state.toJson(true).getBoolean("selfTestReady"));
        assertEquals("check_limits", state.toJson(false).getJSONArray("requiredActions").getString(0));
        assertEquals("Desktop is disabled in Limits", state.selfTestUnavailableReason());
        assertNull(state.phoneUiUnavailableReason());
    }

    @Test public void requiresAwakeUnlockedPhoneAndShell() throws Exception {
        final var ready = new AutomationDeviceState(true, false, false, 35, RuntimeLimits.DEFAULT);
        assertNull(ready.phoneUiUnavailableReason());
        assertTrue(ready.toJson(true).getBoolean("selfTestReady"));
        assertFalse(ready.toJson(false).getBoolean("selfTestReady"));
        assertEquals("check_privileged_service", ready.toJson(false)
                .getJSONArray("requiredActions").getString(0));
        assertNotNull(new AutomationDeviceState(false, false, false, 35, RuntimeLimits.DEFAULT).phoneUiUnavailableReason());
        assertNotNull(new AutomationDeviceState(true, true, false, 35, RuntimeLimits.DEFAULT).phoneUiUnavailableReason());
        assertNotNull(new AutomationDeviceState(true, false, true, 35, RuntimeLimits.DEFAULT).phoneUiUnavailableReason());
    }

    @Test public void unknownStateNeverBecomesReady() throws Exception {
        final var missing = new AutomationDeviceState(null, null, null, 35, RuntimeLimits.DEFAULT);
        assertNotNull(missing.phoneUiUnavailableReason());
        assertFalse(missing.toJson(true).getBoolean("selfTestReady"));
        assertTrue(missing.toJson(true).isNull("interactive"));
    }

    @Test public void android14CanBeInteractiveWithoutSupportingDesktopTests() throws Exception {
        final var ready = new AutomationDeviceState(true, false, false, 34, RuntimeLimits.DEFAULT);
        assertNull(ready.phoneUiUnavailableReason());
        assertNotNull(ready.selfTestUnavailableReason());
        assertFalse(ready.toJson(true).getBoolean("selfTestReady"));
        assertEquals(ready.selfTestUnavailableReason(), ready.toJson(true).getString("selfTestUnavailableReason"));
        assertEquals(0, ready.toJson(true).getJSONArray("requiredActions").length());
    }
}
