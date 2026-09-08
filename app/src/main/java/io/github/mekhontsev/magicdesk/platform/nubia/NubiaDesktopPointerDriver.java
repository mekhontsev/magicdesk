package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformPointerDriver;
import io.github.mekhontsev.magicdesk.PointerPosition;

import android.graphics.Point;
import android.util.Log;

/** Read-only observation of Nubia's globally cached cursor controller. */
final class NubiaDesktopPointerDriver implements PlatformPointerDriver {
    private static final String TAG = "MagicDeskPointer";

    @Override
    public PointerPosition observePosition() {
        try {
            final Point position = NubiaDesktopPointerController.getPosition();
            // The Binder API exposes no controller display identity. In
            // particular, its last controller can belong to the phone.
            return position == null ? null
                    : new PointerPosition(-1, position.x, position.y);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.d(TAG, "system pointer position is unavailable", error);
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
