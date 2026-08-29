package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.DesktopPointerInjector;
import io.github.mekhontsev.magicdesk.PlatformPointerDriver;

import android.graphics.Point;
import android.util.Log;
import android.view.MotionEvent;

/** MagicDesk pointer backend implemented with Nubia's hidden input API. */
final class NubiaDesktopPointerDriver implements PlatformPointerDriver {
    private static final String TAG = "MagicDeskPointer";

    private final NubiaDesktopPointerPositionGuard mPositionGuard =
            new NubiaDesktopPointerPositionGuard();

    @Override
    public boolean capturePosition() {
        return mPositionGuard.capture();
    }

    @Override
    public Point restorePositionIfDisplaced() {
        return mPositionGuard.restoreIfDisplaced();
    }

    @Override
    public int[] getPosition(final int displayId) {
        try {
            NubiaDesktopPointerController.prepareMousePositionControl();
            NubiaDesktopPointerController.createOrUpdateViewport();
            final Point position =
                    NubiaDesktopPointerController.getPosition(displayId);
            return new int[] {position.x, position.y};
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "absolute mouse position is unavailable", error);
        }
    }

    @Override
    public Point observePosition(final int displayId) {
        if (!supportsDisplay(displayId)) {
            return null;
        }
        try {
            return NubiaDesktopPointerController.getPosition();
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.d(TAG, "system pointer position is unavailable", error);
            return null;
        }
    }

    @Override
    public boolean injectClick(final int displayId, final int button) {
        try {
            DesktopPointerInjector.injectClickAt(
                    displayId,
                    NubiaDesktopPointerController.getPosition(),
                    button);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e(TAG, "pointer click injection failed", error);
            return false;
        }
    }

    @Override
    public boolean updatePosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        try {
            final Point position = new Point(x, y);
            NubiaDesktopPointerController.setMousePosition(
                    displayId, position);
            DesktopPointerInjector.injectTouchpadMotion(
                    displayId,
                    position,
                    action,
                    downTime,
                    MotionEvent.TOOL_TYPE_FINGER);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e(TAG, "absolute mouse movement failed", error);
            return false;
        }
    }

    @Override
    public void refreshViewport() {
        try {
            NubiaDesktopPointerController.createOrUpdateViewport();
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "pointer viewport refresh unavailable", error);
        }
    }

    @Override
    public void close() {
        mPositionGuard.close();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean supportsDisplay(final int displayId) {
        // Nubia's absolute cursor service controls projection displays only.
        // Display 0 uses Android's normal display-targeted input injection.
        return displayId > 0;
    }
}
