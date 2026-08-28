package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DisplayDensityController implements AutoCloseable {
    private static final String TAG = "MagicDesk";
    private static final String WM = "/system/bin/wm";
    private static final Set<String> APPLY_KEYS =
            Collections.synchronizedSet(new HashSet<String>());
    // Density overrides are global display operations and must remain ordered
    // across Activity replacement during a configuration change.
    private static final ExecutorService OPERATIONS =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskDisplayDensity");
                thread.setDaemon(true);
                return thread;
            });

    private final DesktopShellActivity mActivity;
    private boolean mApplyStarted;
    private volatile boolean mClosed;

    DisplayDensityController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void resetApplyState() {
        mApplyStarted = false;
    }

    void apply(final int dpi) {
        if (mClosed || !ShellAccess.isReady()) {
            return;
        }
        final int displayId = mActivity.getCurrentDisplayId();
        mActivity.setPreferredDesktopDpi(dpi);
        mActivity.setStatus(mActivity.getString(
                R.string.status_dpi_applying,
                Integer.valueOf(dpi),
                Integer.valueOf(displayId)));
        runDisplayAction(
                WM + " density " + dpi + " -d " + displayId,
                mActivity.getString(
                        R.string.status_dpi_applied,
                        Integer.valueOf(dpi),
                        Integer.valueOf(displayId)));
    }

    void applyRecommended() {
        final int recommendedDpi = mActivity.getRecommendedDesktopDpi();
        if (recommendedDpi == DesktopPreferences.SYSTEM_DESKTOP_DPI) {
            reset();
            return;
        }
        apply(recommendedDpi);
    }

    void reset() {
        if (mClosed || !ShellAccess.isReady()) {
            return;
        }
        final int displayId = mActivity.getCurrentDisplayId();
        mActivity.setPreferredDesktopDpi(
                DesktopPreferences.SYSTEM_DESKTOP_DPI);
        mActivity.setStatus(mActivity.getString(
                R.string.status_dpi_resetting,
                Integer.valueOf(displayId)));
        runDisplayAction(
                WM + " density reset -d " + displayId,
                mActivity.getString(
                        R.string.status_dpi_reset,
                        Integer.valueOf(displayId)));
    }

    void ensurePreferred() {
        if (mClosed || !ShellAccess.isReady()) {
            return;
        }
        final int displayId = mActivity.getCurrentDisplayId();
        if (mApplyStarted || displayId <= 0) {
            return;
        }
        final int targetDpi = mActivity.getPreferredDesktopDpi();
        if (targetDpi == DesktopPreferences.SYSTEM_DESKTOP_DPI) {
            return;
        }
        final int currentDpi =
                mActivity.getResources().getDisplayMetrics().densityDpi;
        if (currentDpi == targetDpi) {
            return;
        }
        final String applyKey = displayId + ":" + targetDpi;
        if (!APPLY_KEYS.add(applyKey)) {
            return;
        }
        mApplyStarted = true;
        OPERATIONS.execute(() -> {
            try {
                final int configuredDpi =
                        getConfiguredDisplayDensity(displayId);
                if (configuredDpi == targetDpi) {
                    Log.i(TAG,
                            "External display DPI already configured display="
                                    + displayId + " dpi=" + targetDpi);
                    return;
                }
                postToActivity(() -> mActivity.setStatus(
                        mActivity.getString(
                                R.string.status_dpi_desktop_applying,
                                Integer.valueOf(targetDpi),
                                Integer.valueOf(displayId),
                                Integer.valueOf(configuredDpi > 0
                                        ? configuredDpi
                                        : currentDpi))));
                runCommand(
                        WM + " density " + targetDpi
                                + " -d " + displayId);
                postToActivity(() -> mActivity.setStatus(
                        mActivity.getString(
                                R.string.status_dpi_desktop_applied,
                                Integer.valueOf(targetDpi),
                                Integer.valueOf(displayId))));
            } catch (IOException e) {
                Log.w(TAG, "desktop DPI failed", e);
                postToActivity(() -> mActivity.setErrorStatus(
                                "DISPLAY-DPI-001",
                                mActivity.getString(
                                        R.string.status_dpi_desktop_failed,
                                        e.getMessage()),
                                "display=" + displayId
                                        + " targetDpi=" + targetDpi,
                                e));
            } finally {
                APPLY_KEYS.remove(applyKey);
                postToActivity(() -> {
                    mApplyStarted = false;
                });
            }
        });
    }

    String getStatus() {
        return mActivity.getString(
                R.string.density_status,
                Integer.valueOf(
                        mActivity.getResources()
                                .getDisplayMetrics().densityDpi));
    }

    private void runDisplayAction(
            final String command,
            final String successStatus) {
        if (mClosed) {
            return;
        }
        OPERATIONS.execute(() -> {
            try {
                runCommand(command);
                postToActivity(() -> {
                    mActivity.renderApps();
                    mActivity.setStatus(successStatus);
                });
            } catch (IOException e) {
                postToActivity(() -> mActivity.setErrorStatus(
                                "DISPLAY-DPI-002",
                                mActivity.getString(
                                        R.string.status_dpi_desktop_failed,
                                        e.getMessage()),
                                "",
                                e));
            }
        });
    }

    private void postToActivity(final Runnable action) {
        mActivity.runOnUiThread(() -> {
            if (!mClosed && !mActivity.isActivityUnavailable()) {
                action.run();
            }
        });
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mApplyStarted = false;
    }

    private static int getConfiguredDisplayDensity(final int displayId)
            throws IOException {
        final String output =
                runCommand(WM + " density -d " + displayId);
        int physicalDensity = -1;
        for (final String line : output.split("\\r?\\n")) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("Override density:")) {
                return parsePositiveInt(trimmed.substring(
                        "Override density:".length()));
            }
            if (trimmed.startsWith("Physical density:")) {
                physicalDensity = parsePositiveInt(trimmed.substring(
                        "Physical density:".length()));
            }
        }
        return physicalDensity;
    }

    private static int parsePositiveInt(final String value) {
        try {
            final int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String runCommand(final String command)
            throws IOException {
        return ShellAccess.run(command);
    }
}
