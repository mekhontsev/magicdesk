package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopCursorTraceProbeTest {
    @Test
    public void findsExpectedTransitionAfterMarker() {
        final String log = "old cursor-probe-old\n"
                + "DragResizeInputListener: update pointer icon from 1000 to 1014\n"
                + "I/MagicDeskSelfTest: cursor-probe-current\n"
                + "D/ShellProtoLog: DragResizeInputListener: "
                + "update pointer icon from 1000 to 1014\n";

        assertEquals(
                "DragResizeInputListener: update pointer icon from 1000 to 1014",
                DesktopCursorTraceProbe.findPointerTransition(
                        log, "cursor-probe-current"));
        assertTrue(DesktopCursorTraceProbe.isPointerType(
                DesktopCursorTraceProbe.findPointerTransition(
                        log, "cursor-probe-current"), 1014));
    }

    @Test
    public void ignoresOldOrUnexpectedTransitions() {
        final String log = "DragResizeInputListener: "
                + "update pointer icon from 1000 to 1014\n"
                + "I/MagicDeskSelfTest: cursor-probe-current\n"
                + "DragResizeInputListener: update pointer icon from 1014 to 1000\n";

        assertEquals(
                "DragResizeInputListener: update pointer icon from 1014 to 1000",
                DesktopCursorTraceProbe.findPointerTransition(
                        log, "cursor-probe-current"));
        assertFalse(DesktopCursorTraceProbe.isPointerType(
                DesktopCursorTraceProbe.findPointerTransition(
                        log, "cursor-probe-current"), 1014));
        assertNull(DesktopCursorTraceProbe.findPointerTransition(
                log, "missing-marker"));
    }
}
