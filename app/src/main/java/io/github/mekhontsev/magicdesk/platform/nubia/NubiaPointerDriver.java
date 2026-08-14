package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.DesktopPointerInjector;
import io.github.mekhontsev.magicdesk.PlatformPointerDriver;

import android.graphics.Point;
import android.util.Log;

final class NubiaPointerDriver implements PlatformPointerDriver {
    private static final String TAG = "MagicDeskPointer";

    private final NubiaPointerPositionGuard mPositionGuard =
            new NubiaPointerPositionGuard();

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
            NubiaMouseController.prepareMousePositionControl();
            NubiaMouseController.createOrUpdateViewport();
            final Point position = NubiaMouseController.getPosition(displayId);
            return new int[] {position.x, position.y};
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "absolute mouse position is unavailable", error);
        }
    }

    @Override
    public boolean injectClick(final int displayId, final int button) {
        try {
            DesktopPointerInjector.injectClickAt(
                    displayId,
                    NubiaMouseController.getPosition(),
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
            NubiaMouseController.setMousePosition(displayId, position);
            DesktopPointerInjector.injectTouchpadMotion(
                    displayId, position, action, downTime);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e(TAG, "absolute mouse movement failed", error);
            return false;
        }
    }

    @Override
    public void refreshViewport() {
        try {
            NubiaMouseController.createOrUpdateViewport();
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
}
