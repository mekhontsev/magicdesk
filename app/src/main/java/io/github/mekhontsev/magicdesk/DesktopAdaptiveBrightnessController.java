package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.Executor;

/** Temporarily disables phone adaptive brightness while any Desktop is active. */
final class DesktopAdaptiveBrightnessController {
    interface BrightnessModeAccess {
        int read() throws IOException;

        void write(int mode) throws IOException;

        void preserveBrightness() throws IOException;

        default void observe(final Runnable changed) {}
        default void stopObserving() {}
    }

    private static final String TAG = "MagicDeskBrightness";
    private static final String SETTINGS = "/system/bin/settings";

    private final BrightnessModeAccess mBrightnessMode;
    private final Executor mExecutor;
    private final LatestOperationSerializer mOperations =
            new LatestOperationSerializer();

    // Ownership changes are serialized; the settings observer can revoke it.
    private volatile boolean mChangedAdaptiveBrightness;
    private volatile boolean mUserChangedMode;
    private boolean mRequested;

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
        mBrightnessMode.observe(this::onModeChanged);
    }

    void reconcile(
            final boolean enabled,
            final java.util.List<DesktopDisplayTarget> targets) {
        final boolean shouldDisable = targets.stream().anyMatch(target -> shouldDisable(enabled, target));
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
        reconcile(false, java.util.List.of());
    }

    void close() {
        mBrightnessMode.stopObserving();
        release();
    }

    private void onModeChanged() {
        if (!mChangedAdaptiveBrightness) return;
        try {
            if (mBrightnessMode.read() != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                mUserChangedMode = true;
            }
        } catch (IOException | RuntimeException error) {
            // Unknown ownership must not overwrite a later user preference.
            mUserChangedMode = true;
        }
    }

    static boolean shouldDisable(
            final boolean enabled,
            final DesktopDisplayTarget target) {
        return enabled && target != null;
    }

    void updateMode(final boolean shouldDisable) throws IOException {
        if (shouldDisable) {
            if (mRequested) {
                return;
            }
            final int mode = mBrightnessMode.read();
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                mRequested = true;
                return;
            }
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                throw new IOException(
                        "unsupported screen brightness mode " + mode);
            }
            mBrightnessMode.preserveBrightness();
            mUserChangedMode = false;
            mBrightnessMode.write(
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            mChangedAdaptiveBrightness = true;
            mRequested = true;
            return;
        }
        mRequested = false;
        if (!mChangedAdaptiveBrightness) {
            return;
        }
        if (!mUserChangedMode
                && mBrightnessMode.read() == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            mBrightnessMode.write(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        }
        mChangedAdaptiveBrightness = false;
    }

    private static final class SystemBrightnessModeAccess
            implements BrightnessModeAccess {
        private final Context mContext;
        private android.database.ContentObserver mObserver;

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

        @Override public void preserveBrightness() throws IOException {
            ShellAccess.preserveDisplayBrightness(android.view.Display.DEFAULT_DISPLAY);
        }

        @Override public void observe(final Runnable changed) {
            mObserver = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(final boolean selfChange) { changed.run(); }
            };
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, mObserver);
        }

        @Override public void stopObserving() {
            if (mObserver != null) mContext.getContentResolver().unregisterContentObserver(mObserver);
            mObserver = null;
        }
    }
}
