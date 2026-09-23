package io.github.mekhontsev.magicdesk;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedShellPlacementTest {
    @Test public void familyExtentsAreRelativeToContentNotTheOutputOrigin() {
        var content = new ShellBounds(100, 200, 228, 248);
        var frame = new HostedShellFrame(new ShellBounds(-8, -6, 72, 34), true, List.of());
        assertEquals(new ShellBounds(84, 188, 244, 268), HostedShellPlacement.bounds(content, frame, 320));
        assertEquals(new ShellBounds(100, 200, 228, 248), content);
    }

    @Test public void fractionalScalingRoundsPaintOutwardWhileInputStillRoundsInward() {
        var frame = new HostedShellFrame(new ShellBounds(-1, -1, 65, 25), true,
                List.of(new ShellBounds(0, 0, 16, 24), new ShellBounds(48, 0, 64, 24)));
        var bounds = HostedShellPlacement.bounds(new ShellBounds(100, 200, 185, 232), frame, 212);
        assertEquals(new ShellBounds(98, 198, 187, 234), bounds);
        assertEquals(List.of(new ShellBounds(2, 2, 22, 34), new ShellBounds(67, 2, 87, 34)),
                frame.inputPixels(bounds.width(), bounds.height()));
    }

    @Test public void invalidOrOverflowingCoordinatesFailRatherThanWrap() {
        var frame = new HostedShellFrame(new ShellBounds(0, 0, 64, 24), false, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> HostedShellPlacement.bounds(frame.viewport(), frame, 0));
        assertThrows(ArithmeticException.class, () -> HostedShellPlacement.bounds(
                new ShellBounds(Integer.MAX_VALUE - 2, 0, Integer.MAX_VALUE, 2), frame, 320));
    }

    @Test public void clipsOffscreenSubsurfacesWithoutChangingPanelReservation() {
        var content = new ShellBounds(0, 0, 1920, 50);
        var input = List.of(content, new ShellBounds(1708, -14, 1842, 50));
        var frame = new HostedShellFrame(new ShellBounds(0, -14, 1920, 50), true, input);
        var clipped = HostedShellPlacement.clip(content, frame, new ShellBounds(0, 0, 1920, 1080), 160);
        assertEquals(content, HostedShellPlacement.bounds(content, clipped, 160));
        assertEquals(content, clipped.viewport());
        assertEquals(List.of(content, new ShellBounds(1708, 0, 1842, 50)), clipped.inputPixels(1920, 50));
        assertEquals(new ShellBounds(0, 0, 1920, 50), content);
    }

    @Test public void clippingUsesOutputPixelsAndRoundsInwardAtFractionalDensity() {
        var content = new ShellBounds(100, 200, 200, 250);
        var frame = new HostedShellFrame(new ShellBounds(-20, -20, 100, 100), false, List.of());
        var output = new ShellBounds(99, 199, 201, 251);
        var clipped = HostedShellPlacement.clip(content, frame, output, 212);
        assertEquals(new ShellBounds(0, 0, 76, 38), clipped.viewport());
        assertEquals(new ShellBounds(100, 200, 201, 251), HostedShellPlacement.bounds(content, clipped, 212));
        assertNull(HostedShellPlacement.clip(content, frame, new ShellBounds(500, 500, 600, 600), 212));
        assertSame(frame, HostedShellPlacement.clip(content, frame, new ShellBounds(0, 0, 1000, 1000), 212));
    }
}
