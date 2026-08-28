package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;

import java.io.IOException;

/** Freezes the phone at its current rotation and restores the exact user mode. */
final class PhoneOrientationLease implements AutoCloseable {
    private static final String WM = "/system/bin/wm";
    private static final String SETTINGS = "/system/bin/settings";

    private final int mAutoRotation;
    private final int mUserRotation;
    private final int mLockedRotation;
    private boolean mClosed;

    private PhoneOrientationLease(
            final int autoRotation,
            final int userRotation,
            final int lockedRotation) {
        mAutoRotation = autoRotation;
        mUserRotation = userRotation;
        mLockedRotation = lockedRotation;
    }

    static PhoneOrientationLease open(final Context context)
            throws IOException {
        if (context == null) {
            throw new IOException("application context is unavailable");
        }
        final DisplayManager displays =
                context.getSystemService(DisplayManager.class);
        final Display phone = displays == null
                ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (phone == null) {
            throw new IOException("phone display is unavailable");
        }
        final int autoRotation = Settings.System.getInt(
                context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION,
                -1);
        final int userRotation = Settings.System.getInt(
                context.getContentResolver(),
                Settings.System.USER_ROTATION,
                -1);
        if (autoRotation < 0 || userRotation < 0) {
            throw new IOException("phone rotation settings are unavailable");
        }
        final PhoneOrientationLease lease = new PhoneOrientationLease(
                autoRotation, userRotation, phone.getRotation());
        try {
            ShellAccess.run(WM + " user-rotation -d 0 lock "
                    + lease.mLockedRotation);
            return lease;
        } catch (IOException | RuntimeException error) {
            try {
                lease.close();
            } catch (IOException restoreError) {
                error.addSuppressed(restoreError);
            }
            throw error;
        }
    }

    String detail() {
        return "rotation=" + mLockedRotation
                + ", previous-auto=" + mAutoRotation
                + ", previous-user=" + mUserRotation;
    }

    @Override
    public void close() throws IOException {
        if (mClosed) {
            return;
        }
        mClosed = true;
        IOException failure = null;
        try {
            ShellAccess.run(WM + " user-rotation -d 0 "
                    + (mAutoRotation == 1
                            ? "free" : "lock " + mUserRotation));
        } catch (IOException error) {
            failure = error;
        }
        try {
            ShellAccess.run(SETTINGS + " put system user_rotation "
                    + mUserRotation);
            ShellAccess.run(SETTINGS + " put system accelerometer_rotation "
                    + mAutoRotation);
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
