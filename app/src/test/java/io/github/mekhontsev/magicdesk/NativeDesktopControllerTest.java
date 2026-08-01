package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeDesktopControllerTest {
    @Test
    public void nativeDesktopRequiresPrivilegedBackendAndSuccessfulProbe() {
        assertFalse(NativeDesktopController.shouldUse(false, true));
        assertFalse(NativeDesktopController.shouldUse(true, false));
        assertTrue(NativeDesktopController.shouldUse(true, true));
    }
}
