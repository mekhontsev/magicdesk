package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopPointerStateTest {
    @Test
    public void globalCoordinatesDoNotBecomeExternalDisplayCoordinates() {
        // Coordinates within both viewports still provide no display identity.
        final PointerPosition global = new PointerPosition(-1, 500, 400);
        final DesktopPointerState state = state(133, global);
        assertNull(state.positionOnDisplay());
        assertSame(global, state.observation);
        assertFalse(global.belongsTo(-1));
        assertFalse(global.belongsTo(0));
        assertTrue(global.reportLabel().contains("display=unknown"));
    }

    @Test
    public void phoneCoordinatesAreNotRelabelledAsDesktopCoordinates() {
        final PointerPosition phone = new PointerPosition(0, 749, 1275);
        assertNull(state(133, phone).positionOnDisplay());
        assertSame(phone, state(0, phone).positionOnDisplay());
    }

    @Test
    public void matchingObservationRemainsAvailable() {
        final PointerPosition desktop = new PointerPosition(133, 749, 605);
        assertSame(desktop, state(133, desktop).positionOnDisplay());
        assertNull(state(133, null).positionOnDisplay());
    }

    @Test
    public void reportSnapshotPreservesUnknownScopeIndependentlyOfRoutingReadiness() {
        final PointerPosition global = new PointerPosition(-1, 749, 1275);
        final InputRelayRuntimeDiagnostics.Snapshot snapshot =
                new InputRelayRuntimeDiagnostics.Snapshot(
                        133, DesktopInputRelayPolicy.NONE, null, null, state(133, global));
        assertTrue(snapshot.pointerRoutingReady);
        assertNull(snapshot.pointerPosition);
        assertSame(global, snapshot.pointerObservation);
    }

    private static DesktopPointerState state(final int displayId,
            final PointerPosition observation) {
        return new DesktopPointerState(displayId, "test", true, true, true, observation);
    }
}
