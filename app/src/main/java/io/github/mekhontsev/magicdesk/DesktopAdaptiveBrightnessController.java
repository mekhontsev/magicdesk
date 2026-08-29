package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.Executor;

/** Temporarily disables adaptive brightness for physical desktop sessions. */
final class DesktopAdaptiveBrightnessController {
    interface BrightnessModeAccess {
        int read() throws IOException;

        void write(int mode) throws IOException;
    }

    private static final String TAG = "MagicDeskBrightness";
    private static final String SETTINGS = "/system/bin/settings";

    private final BrightnessModeAccess mBrightnessMode;
    private final Executor mExecutor;
    private final LatestOperationSerializer mOperations =
            new LatestOperationSerializer();

    // Accessed only while mOperations serializes an operation.
    private boolean mChangedAdaptiveBrightness;

    DesktopAdaptiveBrightnessController(final Context context) {
        this(new SystemBrightnessModeAccess(context),
                DesktopOperations::executeSerialized);
    }

    DesktopAdaptiveBrightnessController(
            final BrightnessModeAccess brightnessMode,
            final Executor executor) {
        if (brightnessMode == null || executor == null) {
            throw new IllegalArgumentException(
                    "brightness mode and executor are required");
        }
        mBrightnessMode = brightnessMode;
        mExecutor = executor;
    }

    void reconcile(
            final boolean enabled,
            final DesktopDisplayTarget target) {
        final boolean shouldDisable = shouldDisable(enabled, target);
        final LatestOperationSerializer.Ticket ticket =
                mOperations.supersede();
        mExecutor.execute(() -> {
            try {
                mOperations.executeIfCurrent(
                        ticket,
                        () -> updateMode(shouldDisable));
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "could not update adaptive brightness", error);
                CompatibilityDiagnostics.record(
                        "DISPLAY-BRIGHTNESS-001",
                        "Could not update adaptive brightness",
                        "requestedDisabled=" + shouldDisable,
                        error);
            }
        });
    }

    void release() {
        reconcile(false, null);
    }

    static boolean shouldDisable(
            final boolean enabled,
            final DesktopDisplayTarget target) {
        return enabled
                && target != null
                && (target.kind == DesktopDisplayTarget.Kind.WIRED
                        || target.kind
                                == DesktopDisplayTarget.Kind.WIRELESS);
    }

    private void updateMode(final boolean shouldDisable) throws IOException {
        if (shouldDisable) {
            if (mChangedAdaptiveBrightness) {
                return;
            }
            final int mode = mBrightnessMode.read();
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                return;
            }
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                throw new IOException(
                        "unsupported screen brightness mode " + mode);
            }
            mBrightnessMode.write(
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            mChangedAdaptiveBrightness = true;
            return;
        }
        if (!mChangedAdaptiveBrightness) {
            return;
        }
        mBrightnessMode.write(
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mChangedAdaptiveBrightness = false;
    }

    private static final class SystemBrightnessModeAccess
            implements BrightnessModeAccess {
        private final Context mContext;

        SystemBrightnessModeAccess(final Context context) {
            if (context == null) {
                throw new IllegalArgumentException("context is required");
            }
            mContext = context.getApplicationContext();
        }

        @Override
        public int read() {
            return Settings.System.getInt(
                    mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    -1);
        }

        @Override
        public void write(final int mode) throws IOException {
            ShellAccess.run(SETTINGS + " put system "
                    + Settings.System.SCREEN_BRIGHTNESS_MODE + " " + mode);
        }
    }
}
