package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ToolLaunchTargetTest {
    @Test public void autoUsesPhoneWithoutDesktop() {
        final var target = ToolLaunchTarget.resolve("auto", -1, java.util.Set.of());
        assertEquals(0, target.displayId);
        assertFalse(target.desktop);
    }

    @Test public void autoUsesOnlyMatchingDesktop() {
        assertTrue(ToolLaunchTarget.resolve("auto", -1, java.util.Set.of(4)).desktop);
        assertTrue(ToolLaunchTarget.resolve("auto", 4, java.util.Set.of(4)).desktop);
        assertFalse(ToolLaunchTarget.resolve("auto", 0, java.util.Set.of(4)).desktop);
        assertFalse(ToolLaunchTarget.resolve("auto", 5, java.util.Set.of(4)).desktop);
    }

    @Test public void ordinaryDisplayDoesNotNeedDesktop() {
        final var target = ToolLaunchTarget.resolve("display", 3, java.util.Set.of());
        assertEquals(3, target.displayId);
        assertFalse(target.desktop);
    }

    @Test public void rejectsBypassingDesktopOwnership() {
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("display", 3, java.util.Set.of(3)));
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("phone", -1, java.util.Set.of(0)));
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("desktop", 3, java.util.Set.of(4)));
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("desktop", -1, java.util.Set.of()));
    }

    @Test public void requiresUnambiguousDestination() {
        assertThrows(IllegalArgumentException.class, () -> ToolLaunchTarget.resolve("auto", -2, java.util.Set.of()));
        assertThrows(IllegalArgumentException.class, () -> ToolLaunchTarget.resolve("display", -1, java.util.Set.of()));
        assertThrows(IllegalArgumentException.class, () -> ToolLaunchTarget.resolve("phone", 3, java.util.Set.of()));
        assertThrows(IllegalArgumentException.class, () -> ToolLaunchTarget.resolve("other", -1, java.util.Set.of()));
    }

    @Test public void ownershipIsCheckedAgainBeforeDispatch() {
        final var ordinary = ToolLaunchTarget.resolve("auto", 0, java.util.Set.of());
        ordinary.requireCurrent(java.util.Set.of());
        ordinary.requireCurrent(java.util.Set.of(3));
        assertThrows(IllegalStateException.class, () -> ordinary.requireCurrent(java.util.Set.of(0)));
        final var desktop = ToolLaunchTarget.resolve("desktop", 3, java.util.Set.of(3));
        desktop.requireCurrent(java.util.Set.of(3));
        assertThrows(IllegalStateException.class, () -> desktop.requireCurrent(java.util.Set.of()));
        assertThrows(IllegalStateException.class, () -> desktop.requireCurrent(java.util.Set.of(0)));
    }
}
