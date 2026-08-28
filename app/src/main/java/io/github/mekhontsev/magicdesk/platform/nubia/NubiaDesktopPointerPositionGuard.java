package io.github.mekhontsev.magicdesk.platform.nubia;

import android.graphics.Point;
import android.util.Log;

final class NubiaDesktopPointerPositionGuard implements AutoCloseable {
    private static final String TAG = "MagicDeskPointer";
    private static final int MAX_NATURAL_MOTION_PX = 128;

    private Point mCapturedPosition;

    NubiaDesktopPointerPositionGuard() {
    }

    boolean capture() {
        try {
            NubiaDesktopPointerController.preparePointerPositionControl();
            final Point position = NubiaDesktopPointerController.getPosition();
            synchronized (this) {
                mCapturedPosition = position;
            }
            Log.d(TAG, "captured pointer position="
                    + position.x + "," + position.y);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            synchronized (this) {
                mCapturedPosition = null;
            }
            Log.w(TAG, "could not capture pointer position",
                    usefulCause(error));
            return false;
        }
    }

    Point restoreIfDisplaced() {
        final Point captured = consumeCapturedPosition();
        if (captured == null) {
            return null;
        }
        try {
            final Point current = NubiaDesktopPointerController.getPosition();
            final long deltaX = (long) current.x - captured.x;
            final long deltaY = (long) current.y - captured.y;
            final long maximum = (long) MAX_NATURAL_MOTION_PX
                    * MAX_NATURAL_MOTION_PX;
            if (deltaX * deltaX + deltaY * deltaY <= maximum) {
                Log.d(TAG, "pointer remained continuous position="
                        + current.x + "," + current.y);
                return current;
            }
            NubiaDesktopPointerController.setPosition(captured);
            Log.i(TAG, "restored displaced pointer from="
                    + current.x + "," + current.y
                    + " to=" + captured.x + "," + captured.y);
            return captured;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not restore pointer position",
                    usefulCause(error));
            return null;
        }
    }

    @Override
    public synchronized void close() {
        mCapturedPosition = null;
    }

    private synchronized Point consumeCapturedPosition() {
        final Point captured = mCapturedPosition;
        mCapturedPosition = null;
        return captured;
    }

    private static Throwable usefulCause(final Throwable error) {
        if (error instanceof ReflectiveOperationException) {
            return NubiaDesktopPointerController.usefulCause(
                    (ReflectiveOperationException) error);
        }
        return error;
    }
}
