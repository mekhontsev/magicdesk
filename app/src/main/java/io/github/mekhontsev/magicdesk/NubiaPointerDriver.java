package io.github.mekhontsev.magicdesk;

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
    public void restorePositionIfDisplaced() {
        mPositionGuard.restoreIfDisplaced();
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
    public Point restoreKnownPosition(final int displayId)
            throws ReflectiveOperationException {
        return NubiaMouseController.restoreKnownPosition(displayId);
    }

    @Override
    public void close() {
        mPositionGuard.close();
    }
}
