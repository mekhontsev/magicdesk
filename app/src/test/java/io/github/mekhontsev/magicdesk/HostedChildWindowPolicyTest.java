package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedChildWindowPolicyTest {
    @Test public void allAdmissionConditionsAreRequired() {
        for (int sdk : new int[] {34, 35, 36, 37})
            for (boolean enabled : new boolean[] {false, true})
                for (boolean managed : new boolean[] {false, true})
                    for (boolean application : new boolean[] {false, true})
                        assertEquals(enabled && sdk >= 35 && managed && application,
                                HostedChildWindowPolicy.external(enabled, sdk, managed, application));
    }

    @Test public void preferenceCopiesAndRoundTripsWithoutChangingDefault() throws Exception {
        var defaults = MagicDeskSettings.Values.defaults();
        assertFalse(defaults.externalLinuxChildWindows);
        defaults.externalLinuxChildWindows = true;
        var copy = defaults.copy();
        defaults.externalLinuxChildWindows = false;
        assertTrue(copy.externalLinuxChildWindows);
        assertTrue(MagicDeskSettings.Values.fromJson(copy.toJson()).externalLinuxChildWindows);
        assertFalse(MagicDeskSettings.Values.fromJson(new org.json.JSONObject()).externalLinuxChildWindows);
    }
}
