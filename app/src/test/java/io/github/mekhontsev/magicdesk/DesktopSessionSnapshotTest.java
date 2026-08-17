package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.Display;

import org.junit.Test;

public final class DesktopSessionSnapshotTest {
    @Test
    public void targetCanBePublishedBeforeDesktopHostExists() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);
        final DesktopSessionSnapshot snapshot =
                DesktopSessionSnapshot.empty().noteTarget(target);

        assertFalse(snapshot.hasHost());
        assertEquals(Display.INVALID_DISPLAY, snapshot.activeDisplayId());
        assertSame(target, snapshot.targetForDisplay(7));
    }

    @Test
    public void matchingExternalHostKeepsPreparedTarget() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .noteTarget(target)
                .registerHost(7, 42, false);

        assertTrue(snapshot.hasHost());
        assertEquals(7, snapshot.activeDisplayId());
        assertEquals(42, snapshot.hostTaskId());
        assertSame(target, snapshot.target());
    }

    @Test
    public void unexpectedExternalHostDoesNotClaimAnotherTarget() {
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.wired(7))
                .registerHost(8, 42, false);

        assertEquals(8, snapshot.activeDisplayId());
        assertNull(snapshot.target());
    }

    @Test
    public void localHostCreatesPhoneTarget() {
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .registerHost(Display.DEFAULT_DISPLAY, 42, false);

        assertTrue(snapshot.isLocalActiveOrStarting());
        assertEquals(DesktopDisplayTarget.Kind.PHONE,
                snapshot.target().kind);
    }

    @Test
    public void sameTaskMoveToPhoneKeepsExternalTarget() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.wireless(7);
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .noteTarget(target)
                .registerHost(7, 42, false)
                .registerHost(Display.DEFAULT_DISPLAY, 42, true);

        assertEquals(Display.DEFAULT_DISPLAY, snapshot.activeDisplayId());
        assertSame(target, snapshot.target());
    }

    @Test
    public void observedHostMoveDoesNotDiscardSessionTarget() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .noteTarget(target)
                .registerHost(7, 42, false)
                .observeHost(Display.DEFAULT_DISPLAY, 42);

        assertEquals(Display.DEFAULT_DISPLAY, snapshot.activeDisplayId());
        assertSame(target, snapshot.target());
    }

    @Test
    public void configurationChangeKeepsTargetWithoutHost() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .noteTarget(target)
                .registerHost(7, 42, false)
                .unregisterHost(7, true);

        assertFalse(snapshot.hasHost());
        assertSame(target, snapshot.target());
    }

    @Test
    public void normalHostRemovalClearsMatchingTarget() {
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.wired(7))
                .registerHost(7, 42, false)
                .unregisterHost(7, false);

        assertFalse(snapshot.hasHost());
        assertNull(snapshot.target());
    }
}
