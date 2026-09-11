package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ToolLaunchTargetTest {
    @Test public void autoUsesPhoneWithoutDesktop() {
        final var target = ToolLaunchTarget.resolve("auto", -1, -1);
        assertEquals(0, target.displayId);
        assertFalse(target.desktop);
    }

    @Test public void autoUsesOnlyMatchingDesktop() {
        assertTrue(ToolLaunchTarget.resolve("auto", -1, 4).desktop);
        assertTrue(ToolLaunchTarget.resolve("auto", 4, 4).desktop);
        assertFalse(ToolLaunchTarget.resolve("auto", 0, 4).desktop);
        assertFalse(ToolLaunchTarget.resolve("auto", 5, 4).desktop);
    }

    @Test public void ordinaryDisplayDoesNotNeedDesktop() {
        final var target = ToolLaunchTarget.resolve("display", 3, -1);
        assertEquals(3, target.displayId);
        assertFalse(target.desktop);
    }

    @Test public void rejectsBypassingDesktopOwnership() {
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("display", 3, 3));
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("phone", -1, 0));
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("desktop", 3, 4));
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("desktop", -1, -1));
    }

    @Test public void requiresUnambiguousDestination() {
        assertThrows(IllegalArgumentException.class, () -> ToolLaunchTarget.resolve("auto", -2, -1));
        assertThrows(IllegalArgumentException.class, () -> ToolLaunchTarget.resolve("display", -1, -1));
        assertThrows(IllegalArgumentException.class, () -> ToolLaunchTarget.resolve("phone", 3, -1));
        assertThrows(IllegalArgumentException.class, () -> ToolLaunchTarget.resolve("other", -1, -1));
    }

    @Test public void ownershipIsCheckedAgainBeforeDispatch() {
        final var ordinary = ToolLaunchTarget.resolve("auto", 0, -1);
        ordinary.requireCurrent(-1);
        ordinary.requireCurrent(3);
        assertThrows(IllegalStateException.class, () -> ordinary.requireCurrent(0));
        final var desktop = ToolLaunchTarget.resolve("desktop", 3, 3);
        desktop.requireCurrent(3);
        assertThrows(IllegalStateException.class, () -> desktop.requireCurrent(-1));
        assertThrows(IllegalStateException.class, () -> desktop.requireCurrent(0));
    }
}
