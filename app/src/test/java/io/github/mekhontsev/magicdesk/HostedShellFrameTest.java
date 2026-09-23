package io.github.mekhontsev.magicdesk;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedShellFrameTest {
    @Test public void negativeViewportAndDisjointInputScaleTogether() {
        var frame = new HostedShellFrame(new ShellBounds(-8, -6, 72, 34), true,
                List.of(new ShellBounds(0, 0, 16, 24), new ShellBounds(48, 0, 64, 24)));
        assertEquals(List.of(new ShellBounds(60, 45, 180, 225), new ShellBounds(420, 45, 540, 225)),
                frame.inputPixels(600, 300));
    }

    @Test public void fractionalPixelEdgesDoNotExpandInputAcrossHoles() {
        var frame = new HostedShellFrame(new ShellBounds(0, 0, 64, 24), true,
                List.of(new ShellBounds(-8, -8, 16, 40), new ShellBounds(48, 0, 80, 24)));
        assertEquals(List.of(new ShellBounds(0, 0, 25, 31), new ShellBounds(76, 0, 101, 31)),
                frame.inputPixels(101, 31));
        assertTrue(frame.inputPixels(1, 1).isEmpty());
    }

    @Test public void incompleteOrAbsentInputNeverBecomesTheWindowRectangle() {
        var frame = new HostedShellFrame(new ShellBounds(0, 0, 80, 40), false, List.of());
        assertTrue(frame.inputPixels(800, 400).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HostedShellFrame(frame.viewport(), false,
                List.of(frame.viewport())));
        assertThrows(IllegalArgumentException.class, () -> new HostedShellFrame(
                new ShellBounds(0, 0, 0, 0), true, List.of()));
    }

    @Test public void emptyOutsideAndMutableInputsAreHandledWithoutLeakingState() {
        var input = new java.util.ArrayList<ShellBounds>();
        input.add(new ShellBounds(20, 20, 30, 30));
        var frame = new HostedShellFrame(new ShellBounds(0, 0, 10, 10), true, input);
        input.clear();
        assertEquals(1, frame.input().size());
        assertTrue(frame.inputPixels(40, 40).isEmpty());
        assertTrue(frame.inputPixels(0, 40).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> frame.input().clear());
    }
}
