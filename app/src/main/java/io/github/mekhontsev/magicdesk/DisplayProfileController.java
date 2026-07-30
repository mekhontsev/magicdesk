package io.github.mekhontsev.magicdesk;

import android.hardware.display.DisplayManager;
import android.graphics.Rect;
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
    private static final long MONITOR_IDENTITY_RETRY_MILLIS = 2_000L;
    private static final int MAX_MONITOR_IDENTITY_ATTEMPTS = 3;

    private final DesktopShellActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = this::refreshAfterDisplayChange;
    private final Runnable mMonitorIdentityRetry =
            this::resolveMonitorIdentityAsync;

    private DisplayManager.DisplayListener mDisplayListener;
    private WorkspaceProfileStore.Profile mProfile;
    private String mProfileDisplayKey;
    private String mMonitorProfileKey;
    private boolean mMonitorIdentityRequested;
    private int mMonitorIdentityGeneration;
    private int mMonitorIdentityAttempts;

    DisplayProfileController(final DesktopShellActivity activity) {
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
        mHandler.removeCallbacks(mMonitorIdentityRetry);
        mMonitorIdentityGeneration++;
        mMonitorIdentityRequested = false;
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
        mMonitorIdentityAttempts = 0;
        mMonitorIdentityGeneration++;
        mHandler.removeCallbacks(mMonitorIdentityRetry);
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
        final String requestedDisplayKey = resolveProfileKey();
        final int generation = ++mMonitorIdentityGeneration;
        mMonitorIdentityAttempts++;
        new Thread(() -> {
            final String output;
            try {
                output = PrivilegedCommandRunner.run(
                        "for d in /sys/class/drm/card*-DP-*; do "
                                + "[ -d \"$d\" ] || continue; "
                                + "[ \"$(/system/bin/cat \"$d/status\" 2>/dev/null)\" "
                                + "= connected ] || continue; "
                                + "h=$(/system/bin/sha256sum \"$d/edid\" 2>/dev/null) "
                                + "|| continue; h=${h%% *}; "
                                + "printf '%s %s\\n' \"$h\" \"$d\"; done")
                        .trim();
            } catch (IOException error) {
                Log.w(TAG, "Cannot resolve monitor EDID", error);
                mActivity.runOnUiThread(() ->
                        finishMonitorIdentityAttempt(
                                generation, requestedDisplayKey, null));
                return;
            }
            final String hash = parseSingleConnectedEdidHash(output);
            if (hash == null) {
                Log.w(TAG,
                        "Expected exactly one connected DP EDID: " + output);
                mActivity.runOnUiThread(() ->
                        finishMonitorIdentityAttempt(
                                generation, requestedDisplayKey, null));
                return;
            }
            final String monitorKey = "edid:" + hash;
            Log.i(TAG, "Resolved monitor profile " + monitorKey);
            mActivity.runOnUiThread(() ->
                    finishMonitorIdentityAttempt(
                            generation, requestedDisplayKey, monitorKey));
        }, "MagicDeskMonitorIdentity").start();
    }

    static String parseSingleConnectedEdidHash(final String output) {
        if (output == null || output.trim().isEmpty()) {
            return null;
        }
        String hash = null;
        for (final String line : output.split("\\r?\\n")) {
            final String[] fields = line.trim().split("\\s+");
            if (fields.length < 2
                    || !fields[0].matches("[0-9a-fA-F]{64}")) {
                continue;
            }
            if (hash != null) {
                return null;
            }
            hash = fields[0].toLowerCase(Locale.ROOT);
        }
        return hash;
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
        final Display current = mActivity.getDisplay();
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

    private void finishMonitorIdentityAttempt(
            final int generation,
            final String requestedDisplayKey,
            final String monitorKey) {
        if (generation != mMonitorIdentityGeneration
                || mActivity.isActivityUnavailable()) {
            return;
        }
        if (!requestedDisplayKey.equals(resolveProfileKey())
                || !requestedDisplayKey.equals(mProfileDisplayKey)) {
            mMonitorIdentityRequested = false;
            return;
        }
        if (monitorKey != null) {
            applyResolvedMonitorProfile(requestedDisplayKey, monitorKey);
            return;
        }
        mMonitorIdentityRequested = false;
        if (mMonitorIdentityAttempts < MAX_MONITOR_IDENTITY_ATTEMPTS) {
            mHandler.removeCallbacks(mMonitorIdentityRetry);
            mHandler.postDelayed(
                    mMonitorIdentityRetry,
                    MONITOR_IDENTITY_RETRY_MILLIS);
        }
    }

    private void applyResolvedMonitorProfile(
            final String requestedDisplayKey,
            final String monitorKey) {
        if (monitorKey == null
                || monitorKey.equals(mMonitorProfileKey)
                || mActivity.isActivityUnavailable()
                || !requestedDisplayKey.equals(mProfileDisplayKey)) {
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
                mActivity, requestedDisplayKey, monitorKey);
        mMonitorProfileKey = monitorKey;
        mProfile = resolved;
        Log.i(TAG, "Activated monitor profile " + monitorKey);
        mActivity.onMonitorProfileResolved(previousDpi, resolved.dpi);
    }
}
