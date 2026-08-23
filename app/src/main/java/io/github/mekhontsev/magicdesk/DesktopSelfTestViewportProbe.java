package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;

/** Reads one stable live viewport after an application-driven configuration change. */
final class DesktopSelfTestViewportProbe {
    private DesktopSelfTestViewportProbe() {
    }

    static DesktopSelfTestGeometry await(
            final Context context,
            final int displayId,
            final DesktopSelfTestGeometry baseline) throws IOException {
        final Snapshot snapshot = awaitSnapshot(context, displayId);
        return baseline.withViewport(
                snapshot.display, snapshot.workArea, snapshot.rotation);
    }

    static DesktopSelfTestGeometry awaitInputViewport(
            final Context context,
            final int displayId,
            final DesktopSelfTestGeometry baseline) throws IOException {
        final Snapshot snapshot = awaitSnapshot(context, displayId);
        return baseline.withInputViewport(
                snapshot.display, snapshot.rotation);
    }

    private static Snapshot awaitSnapshot(
            final Context context,
            final int displayId) throws IOException {
        final DisplayManager manager = context == null ? null
                : context.getSystemService(DisplayManager.class);
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        Rect previousDisplay = null;
        Rect previousWorkArea = null;
        int previousRotation = -1;
        do {
            final DesktopViewport viewport =
                    DesktopRuntimeBridge.getDesktopViewport(displayId);
            final Rect workArea =
                    DesktopRuntimeBridge.getDesktopWorkAreaBounds(displayId);
            final Display androidDisplay = manager == null
                    ? null : manager.getDisplay(displayId);
            if (viewport != null && workArea != null
                    && androidDisplay != null) {
                final Rect display = viewport.displayBounds();
                final int rotation = androidDisplay.getRotation();
                if (display.width() > 0
                        && display.height() > 0
                        && workArea.width() > 0
                        && workArea.height() > 0
                        && display.contains(workArea)) {
                    if (display.equals(previousDisplay)
                            && workArea.equals(previousWorkArea)
                            && rotation == previousRotation) {
                        return new Snapshot(
                                display, workArea, rotation);
                    }
                    previousDisplay = new Rect(display);
                    previousWorkArea = new Rect(workArea);
                    previousRotation = rotation;
                } else {
                    previousDisplay = null;
                    previousWorkArea = null;
                    previousRotation = -1;
                }
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("desktop viewport did not settle after "
                + "application fullscreen");
    }

    private static final class Snapshot {
        final Rect display;
        final Rect workArea;
        final int rotation;

        Snapshot(
                final Rect display,
                final Rect workArea,
                final int rotation) {
            this.display = new Rect(display);
            this.workArea = new Rect(workArea);
            this.rotation = rotation;
        }
    }
}
