package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTransitionGateTest {
    @Test
    public void oneStartOrModeTransitionRunsAtATime() {
        final DesktopTransitionGate gate = new DesktopTransitionGate();

        assertTrue(gate.beginDesktopStart());
        assertFalse(gate.beginDesktopStart());
        assertFalse(gate.beginModeTransition());

        gate.finishStart();

        assertTrue(gate.beginModeTransition());
    }

    @Test
    public void closeBlocksNewDesktopActivationUntilFinished() {
        final DesktopTransitionGate gate = new DesktopTransitionGate();

        assertTrue(gate.beginClose());
        assertTrue(gate.isCloseInProgress());
        assertFalse(gate.beginDesktopStart());
        assertFalse(gate.beginClose());

        gate.finishClose();

        assertFalse(gate.isCloseInProgress());
        assertTrue(gate.beginDesktopStart());
    }
}
