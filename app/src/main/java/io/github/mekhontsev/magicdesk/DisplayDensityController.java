package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class DisplayDensityController {
    private static final String TAG = "MagicDesk";
    private static final String WM = "/system/bin/wm";
    private static final String SETTINGS = "/system/bin/settings";
    private static final Set<String> APPLY_KEYS =
            Collections.synchronizedSet(new HashSet<String>());

    private final MainActivity mActivity;
    private boolean mApplyStarted;

    DisplayDensityController(final MainActivity activity) {
        mActivity = activity;
    }

    void resetApplyState() {
        mApplyStarted = false;
    }

    void apply(final int dpi) {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.DISPLAY_OVERRIDES)) {
            return;
        }
        final int displayId = mActivity.getCurrentDisplayId();
        if (displayId > 0) {
            mActivity.setPreferredDesktopDpi(dpi);
        }
        mActivity.setStatus(mActivity.getString(
                R.string.status_dpi_applying,
                Integer.valueOf(dpi),
                Integer.valueOf(displayId)));
        runRootAction(
                WM + " density " + dpi + " -d " + displayId,
                mActivity.getString(
                        R.string.status_dpi_applied,
                        Integer.valueOf(dpi),
                        Integer.valueOf(displayId)));
    }

    void reset() {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.DISPLAY_OVERRIDES)) {
            return;
        }
        final int displayId = mActivity.getCurrentDisplayId();
        final String command;
        if (displayId > 0) {
            mActivity.setPreferredDesktopDpi(
                    DesktopPreferences.DEFAULT_DESKTOP_DPI);
            command = WM + " density "
                    + DesktopPreferences.DEFAULT_DESKTOP_DPI
                    + " -d " + displayId;
        } else {
            command = WM + " density reset -d " + displayId;
        }
        mActivity.setStatus(mActivity.getString(
                R.string.status_dpi_resetting,
                Integer.valueOf(displayId)));
        runRootAction(
                command,
                mActivity.getString(
                        R.string.status_dpi_reset,
                        Integer.valueOf(displayId)));
    }

    void ensurePreferred() {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.DISPLAY_OVERRIDES)) {
            return;
        }
        final int displayId = mActivity.getCurrentDisplayId();
        if (mApplyStarted || displayId <= 0) {
            return;
        }
        final int targetDpi = mActivity.getPreferredDesktopDpi();
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
        new Thread(() -> {
            try {
                final int mirrorDisplayId = getMirrorDisplayId();
                if (mirrorDisplayId != displayId) {
                    Log.i(TAG,
                            "skip default DPI for non-mirror display "
                                    + displayId);
                    return;
                }
                final int configuredDpi =
                        getConfiguredDisplayDensity(displayId);
                if (configuredDpi == targetDpi) {
                    Log.i(TAG,
                            "Console display DPI already configured display="
                                    + displayId + " dpi=" + targetDpi);
                    return;
                }
                mActivity.runOnUiThread(() ->
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_dpi_desktop_applying,
                                Integer.valueOf(targetDpi),
                                Integer.valueOf(displayId),
                                Integer.valueOf(configuredDpi > 0
                                        ? configuredDpi
                                        : currentDpi))));
                runRootCommand(
                        WM + " density " + targetDpi
                                + " -d " + displayId);
                mActivity.runOnUiThread(() ->
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_dpi_desktop_applied,
                                Integer.valueOf(targetDpi),
                                Integer.valueOf(displayId))));
            } catch (IOException e) {
                Log.w(TAG, "desktop DPI failed", e);
                mActivity.runOnUiThread(() ->
                        mActivity.setErrorStatus(
                                "DISPLAY-DPI-001",
                                mActivity.getString(
                                        R.string.status_dpi_desktop_failed,
                                        e.getMessage()),
                                "display=" + displayId
                                        + " targetDpi=" + targetDpi,
                                e));
            } finally {
                APPLY_KEYS.remove(applyKey);
                mApplyStarted = false;
            }
        }, "MagicDeskDesktopDpi").start();
    }

    String getStatus() {
        return mActivity.getString(
                R.string.density_status,
                Integer.valueOf(
                        mActivity.getResources()
                                .getDisplayMetrics().densityDpi));
    }

    private void runRootAction(
            final String command,
            final String successStatus) {
        new Thread(() -> {
            try {
                runRootCommand(command);
                mActivity.runOnUiThread(() -> {
                    mActivity.renderApps();
                    mActivity.setStatus(successStatus);
                });
            } catch (IOException e) {
                mActivity.runOnUiThread(() ->
                        mActivity.setErrorStatus(
                                "ROOT-ACTION-001",
                                mActivity.getString(
                                        R.string.status_root_failed,
                                        e.getMessage()),
                                "",
                                e));
            }
        }, "MagicDeskRootAction").start();
    }

    private static int getConfiguredDisplayDensity(final int displayId)
            throws IOException {
        final String output =
                runRootCommand(WM + " density -d " + displayId);
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

    private static int getMirrorDisplayId() throws IOException {
        final String output = runRootCommand(
                SETTINGS + " get global app_mirror_displayid");
        final String trimmed = output == null ? "" : output.trim();
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String runRootCommand(final String command)
            throws IOException {
        return PrivilegedCommandRunner.run(command);
    }
}
