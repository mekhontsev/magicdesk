package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Observes WMShell's native resize-cursor decision when text ProtoLog is available. */
final class DesktopCursorTraceProbe implements AutoCloseable {
    static final int HORIZONTAL_RESIZE_POINTER_TYPE = 1014;

    private static final String PROTOLOG_GROUP = "WM_SHELL_DESKTOP_MODE";
    private static final String MARKER_TAG = "MagicDeskSelfTest";
    private static final String CURSOR_LOG_PREFIX =
            "DragResizeInputListener: update pointer icon from ";

    private final String mMarker;
    private boolean mEnabled;

    private DesktopCursorTraceProbe(final String marker) {
        mMarker = marker;
    }

    static DesktopCursorTraceProbe open() throws IOException {
        final DesktopCursorTraceProbe probe = new DesktopCursorTraceProbe(
                "cursor-probe-" + Long.toHexString(System.nanoTime()));
        ShellAccess.run(
                "/system/bin/cmd window shell protolog enable-text "
                        + PROTOLOG_GROUP);
        probe.mEnabled = true;
        try {
            ShellAccess.run(
                    "/system/bin/log -t " + MARKER_TAG + " " + probe.mMarker);
            return probe;
        } catch (IOException error) {
            probe.close();
            throw error;
        }
    }

    String readPointerTransition() throws IOException {
        final String log = ShellAccess.run(
                "/system/bin/logcat -d -v brief -t 500");
        return findPointerTransition(log, mMarker);
    }

    @Override
    public void close() {
        if (!mEnabled) {
            return;
        }
        mEnabled = false;
        try {
            ShellAccess.run(
                    "/system/bin/cmd window shell protolog disable-text "
                            + PROTOLOG_GROUP);
        } catch (IOException ignored) {
            // The diagnostic must still clean up its desktop session.
        }
    }

    static String findPointerTransition(
            final String log,
            final String marker) {
        if (log == null || marker == null) {
            return null;
        }
        final int markerIndex = log.lastIndexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        final String[] lines = log.substring(markerIndex).split("\\R");
        String lastTransition = null;
        for (final String line : lines) {
            final int transitionIndex = line.indexOf(CURSOR_LOG_PREFIX);
            if (transitionIndex >= 0) {
                lastTransition = line.substring(transitionIndex).trim();
            }
        }
        return lastTransition;
    }

    static boolean isPointerType(final String transition, final int type) {
        return transition != null && transition.endsWith(" to " + type);
    }
}
