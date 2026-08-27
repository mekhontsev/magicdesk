package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskConfigurationGuardTest {
    @Test
    public void clearsConfigurationStillOwnedByStoppingDisplay() {
        assertTrue(DesktopTaskConfigurationGuard.canClear(49, 49, -1));
        assertTrue(DesktopTaskConfigurationGuard.canClear(0, 0, 0));
    }

    @Test
    public void preservesHostAreaAlreadyReplacedByAnotherDisplay() {
        assertFalse(DesktopTaskConfigurationGuard.canClear(49, 49, 0));
    }

    @Test
    public void ignoresClearFromOlderConfiguredDisplay() {
        assertFalse(DesktopTaskConfigurationGuard.canClear(49, 0, 0));
    }
}
