package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTransitionGateTest {
    @Test
    public void oneStartOrModeTransitionRunsAtATime() {
        final DesktopTransitionGate gate = new DesktopTransitionGate();

        assertTrue(gate.begin(DesktopTransitionGate.Operation.START));
        assertFalse(gate.begin(DesktopTransitionGate.Operation.START));
        assertFalse(gate.begin(
                DesktopTransitionGate.Operation.MODE_TRANSITION));

        gate.finish(DesktopTransitionGate.Operation.START);

        assertTrue(gate.begin(
                DesktopTransitionGate.Operation.MODE_TRANSITION));
    }

    @Test
    public void closeBlocksNewDesktopActivationUntilFinished() {
        final DesktopTransitionGate gate = new DesktopTransitionGate();

        assertTrue(gate.begin(DesktopTransitionGate.Operation.CLOSE));
        assertTrue(gate.isActive(DesktopTransitionGate.Operation.CLOSE));
        assertFalse(gate.begin(DesktopTransitionGate.Operation.START));
        assertFalse(gate.begin(DesktopTransitionGate.Operation.CLOSE));

        gate.finish(DesktopTransitionGate.Operation.CLOSE);

        assertFalse(gate.isActive(DesktopTransitionGate.Operation.CLOSE));
        assertTrue(gate.begin(DesktopTransitionGate.Operation.START));
    }

    @Test
    public void startAndCloseCannotOverlapInEitherOrder() {
        final DesktopTransitionGate gate = new DesktopTransitionGate();

        assertTrue(gate.begin(DesktopTransitionGate.Operation.START));
        assertFalse(gate.begin(DesktopTransitionGate.Operation.CLOSE));
        assertFalse(gate.finish(DesktopTransitionGate.Operation.CLOSE));
        assertTrue(gate.isActive(DesktopTransitionGate.Operation.START));
        gate.finish(DesktopTransitionGate.Operation.START);
        assertTrue(gate.begin(DesktopTransitionGate.Operation.CLOSE));
    }
}
