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
                .registerHost(7, 42);

        assertTrue(snapshot.hasHost());
        assertEquals(7, snapshot.activeDisplayId());
        assertEquals(42, snapshot.hostTaskId());
        assertSame(target, snapshot.target());
    }

    @Test
    public void configurationChangeKeepsTargetWithoutHost() {
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .noteTarget(target)
                .registerHost(7, 42)
                .unregisterHost(7, true);

        assertFalse(snapshot.hasHost());
        assertSame(target, snapshot.target());
    }

    @Test
    public void normalHostRemovalClearsMatchingTarget() {
        final DesktopSessionSnapshot snapshot = DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.wired(7))
                .registerHost(7, 42)
                .unregisterHost(7, false);

        assertFalse(snapshot.hasHost());
        assertNull(snapshot.target());
    }

    @Test
    public void isolatedPolicySurvivesHostLifecycleUntilSessionEnds() {
        final DesktopSessionSnapshot configured =
                DesktopSessionSnapshot.empty()
                        .noteTarget(
                                DesktopDisplayTarget.simulated(7),
                                DesktopSessionPolicy.ISOLATED_SELF_TEST)
                        .registerHost(7, 42)
                        .unregisterHost(7, true);

        assertEquals(
                DesktopSessionPolicy.ISOLATED_SELF_TEST,
                configured.policy());
        assertEquals(
                DesktopSessionPolicy.USER,
                configured.unregisterHost(7, false).policy());
    }
}
