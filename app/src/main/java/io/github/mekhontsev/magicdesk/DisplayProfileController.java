package io.github.mekhontsev.magicdesk;

import android.hardware.display.DisplayManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class DisplayProfileController {
    private static final String TAG = "MagicDeskDisplayProfile";
    private static final long DISPLAY_SETTLE_MILLIS = 1_000L;

    private final MainActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = this::refreshAfterDisplayChange;

    private DisplayManager.DisplayListener mDisplayListener;
    private WorkspaceProfileStore.Profile mProfile;
    private String mProfileDisplayKey;
    private String mMonitorProfileKey;
    private boolean mMonitorIdentityRequested;

    DisplayProfileController(final MainActivity activity) {
        mActivity = activity;
    }

    void start() {
        final DisplayManager displayManager =
                mActivity.getSystemService(DisplayManager.class);
        if (displayManager == null || mDisplayListener != null) {
            return;
        }
        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(final int displayId) {
                scheduleRefresh();
            }

            @Override
            public void onDisplayRemoved(final int displayId) {
                scheduleRefresh();
            }

            @Override
            public void onDisplayChanged(final int displayId) {
                scheduleRefresh();
            }
        };
        displayManager.registerDisplayListener(mDisplayListener, mHandler);
    }

    void stop() {
        mHandler.removeCallbacks(mRefresh);
        final DisplayManager displayManager =
                mActivity.getSystemService(DisplayManager.class);
        if (displayManager != null && mDisplayListener != null) {
            displayManager.unregisterDisplayListener(mDisplayListener);
        }
        mDisplayListener = null;
    }

    WorkspaceProfileStore.Profile getProfile() {
        if (mProfile != null) {
            return mProfile;
        }
        final String displayKey = resolveProfileKey();
        final String monitorKey = WorkspaceProfileStore.resolveMonitorAlias(
                mActivity, displayKey);
        final Set<String> defaults = new LinkedHashSet<>(
                DesktopPreferences.favoritePackages());
        mProfileDisplayKey = displayKey;
        mMonitorProfileKey = monitorKey;
        mProfile = WorkspaceProfileStore.load(
                mActivity,
                monitorKey,
                DesktopPreferences.legacyDesktopDpi(mActivity),
                DesktopPreferences.legacyLayoutMode(mActivity),
                DesktopPreferences.legacyPinnedPackages(mActivity),
                defaults);
        mActivity.onWorkspaceProfileReset();
        return mProfile;
    }

    void save() {
        WorkspaceProfileStore.save(mActivity, getProfile());
    }

    void refreshForDisplay() {
        final String displayKey = resolveProfileKey();
        if (mProfile != null && displayKey.equals(mProfileDisplayKey)) {
            return;
        }
        mProfile = null;
        mProfileDisplayKey = displayKey;
        mMonitorProfileKey = null;
        mMonitorIdentityRequested = false;
        getProfile();
    }

    String getMonitorLabel() {
        final Display display = getProfileDisplay();
        return display == null
                ? mActivity.getString(R.string.profile_default)
                : display.getName();
    }

    void resolveMonitorIdentityAsync() {
        if (mMonitorIdentityRequested) {
            return;
        }
        final Display profileDisplay = getProfileDisplay();
        if (profileDisplay == null
                || profileDisplay.getDisplayId() == Display.DEFAULT_DISPLAY
                || profileDisplay.getName().contains("NubiaAppMirror")) {
            return;
        }
        mMonitorIdentityRequested = true;
        new Thread(() -> {
            final String output;
            try {
                output = PrivilegedCommandRunner.run(
                        "for f in /sys/class/drm/card*-DP-*/edid; do "
                                + "/system/bin/sha256sum \"$f\" && exit; done")
                        .trim();
            } catch (IOException error) {
                Log.w(TAG, "Cannot resolve monitor EDID", error);
                return;
            }
            final String[] fields = output.split("\\s+");
            if (fields.length == 0
                    || !fields[0].matches("[0-9a-fA-F]{64}")) {
                Log.w(TAG, "No usable monitor EDID hash: " + output);
                return;
            }
            final String monitorKey =
                    "edid:" + fields[0].toLowerCase(Locale.ROOT);
            Log.i(TAG, "Resolved monitor profile " + monitorKey);
            mActivity.runOnUiThread(
                    () -> applyResolvedMonitorProfile(monitorKey));
        }, "MagicDeskMonitorIdentity").start();
    }

    private void refreshAfterDisplayChange() {
        if (mActivity.isActivityUnavailable()) {
            return;
        }
        refreshForDisplay();
        resolveMonitorIdentityAsync();
    }

    void scheduleRefresh() {
        mHandler.removeCallbacks(mRefresh);
        mHandler.post(mRefresh);
        mHandler.postDelayed(mRefresh, DISPLAY_SETTLE_MILLIS);
    }

    private String resolveProfileKey() {
        final Display profileDisplay = getProfileDisplay();
        if (profileDisplay == null) {
            return "display:default";
        }
        final Display.Mode mode = profileDisplay.getMode();
        final String resolution = mode == null
                ? "unknown"
                : mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight();
        return "display-" + profileDisplay.getDisplayId()
                + "|" + profileDisplay.getName() + "|" + resolution;
    }

    private Display getProfileDisplay() {
        final DisplayManager manager =
                mActivity.getSystemService(DisplayManager.class);
        final Display current = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? mActivity.getDisplay()
                : mActivity.getWindowManager().getDefaultDisplay();
        if (manager == null) {
            return current;
        }
        final boolean currentIsVirtual = current != null
                && current.getName().contains("NubiaAppMirror");
        if (current != null && !currentIsVirtual) {
            return current;
        }
        final Display[] presentations = manager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (final Display display : presentations) {
            if (display != null
                    && !display.getName().contains("NubiaAppMirror")
                    && display.getState() != Display.STATE_OFF) {
                return display;
            }
        }
        return current;
    }

    private void applyResolvedMonitorProfile(final String monitorKey) {
        if (monitorKey == null
                || monitorKey.equals(mMonitorProfileKey)
                || mActivity.isActivityUnavailable()) {
            return;
        }
        final WorkspaceProfileStore.Profile previous = getProfile();
        final boolean existed =
                WorkspaceProfileStore.exists(mActivity, monitorKey);
        final WorkspaceProfileStore.Profile resolved =
                WorkspaceProfileStore.load(
                        mActivity,
                        monitorKey,
                        previous.dpi,
                        previous.layoutMode,
                        previous.taskbarPackages,
                        previous.desktopPackages);
        if (!existed) {
            resolved.folderUri = previous.folderUri;
            resolved.workspacePackage = previous.workspacePackage;
            resolved.workspaceBounds = new Rect(previous.workspaceBounds);
            WorkspaceProfileStore.save(mActivity, resolved);
        }
        final int previousDpi = previous.dpi;
        WorkspaceProfileStore.saveMonitorAlias(
                mActivity, mProfileDisplayKey, monitorKey);
        mMonitorProfileKey = monitorKey;
        mProfile = resolved;
        Log.i(TAG, "Activated monitor profile " + monitorKey);
        mActivity.onMonitorProfileResolved(previousDpi, resolved.dpi);
    }
}
