package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedChildWindowPolicyTest {
    @Test public void allAdmissionConditionsAreRequired() {
        for (int sdk : new int[] {34, 35, 36, 37})
            for (boolean managed : new boolean[] {false, true})
                for (boolean application : new boolean[] {false, true})
                    assertEquals(sdk >= 35 && managed && application,
                            HostedChildWindowPolicy.external(sdk, managed, application));
    }

    @Test public void managedApplicationsAutomaticallyUseExternalPresentation() {
        assertTrue(HostedChildWindowPolicy.external(35, true, true));
        assertTrue(HostedChildWindowPolicy.external(36, true, true));
    }
}
