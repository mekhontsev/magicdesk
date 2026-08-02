package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ImmersiveRequestStateTest {
    @Test
    public void changedProcessStartsANewClientSample() {
        assertTrue(ShellTaskStateMonitor.isInitialClientSample(
                Integer.valueOf(1), Integer.valueOf(100), Integer.valueOf(101)));
        assertFalse(ShellTaskStateMonitor.isInitialClientSample(
                Integer.valueOf(1), Integer.valueOf(100), Integer.valueOf(100)));
    }

    @Test
    public void missingProcessIdentityDoesNotInventAClientRestart() {
        assertTrue(ShellTaskStateMonitor.isInitialClientSample(
                null, null, null));
        assertFalse(ShellTaskStateMonitor.isInitialClientSample(
                Integer.valueOf(1), Integer.valueOf(100), null));
    }

    @Test
    public void onlySameClientFalseToTrueIsANewImmersiveRequest() {
        assertTrue(DesktopWindowTransitionController.isNewImmersiveRequest(
                Boolean.FALSE, true, false));
        assertFalse(DesktopWindowTransitionController.isNewImmersiveRequest(
                Boolean.FALSE, true, true));
        assertFalse(DesktopWindowTransitionController.isNewImmersiveRequest(
                Boolean.TRUE, true, false));
        assertFalse(DesktopWindowTransitionController.isNewImmersiveRequest(
                Boolean.TRUE, false, false));
    }
}
