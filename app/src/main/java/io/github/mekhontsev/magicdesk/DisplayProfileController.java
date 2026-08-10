package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.Locale;

final class DisplayProfileController {
    private static final String TAG = "MagicDeskDisplayProfile";
    private static final long DISPLAY_SETTLE_MILLIS = 1_000L;
    private static final long MONITOR_IDENTITY_RETRY_MILLIS = 2_000L;
    private static final int MAX_MONITOR_IDENTITY_ATTEMPTS = 3;
    private static final Object MONITOR_IDENTITY_BUDGET_LOCK = new Object();

    private static int sMonitorIdentityDisplayId = Display.INVALID_DISPLAY;
    private static int sMonitorIdentityAttempts;

    private final DesktopShellActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = this::refreshAfterDisplayChange;
    private final Runnable mMonitorIdentityRetry =
            this::resolveMonitorIdentityAsync;

    private DisplayManager.DisplayListener mDisplayListener;
    private DisplayProfileStore.Profile mProfile;
    private String mProfileDisplayKey;
    private String mMonitorProfileKey;
    private boolean mMonitorIdentityRequested;
    private int mMonitorIdentityDisplayId = Display.INVALID_DISPLAY;
    private int mMonitorIdentityGeneration;

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
                resetMonitorIdentityBudget(displayId);
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

    DisplayProfileStore.Profile getProfile() {
        if (mProfile != null) {
            return mProfile;
        }
        final String displayKey = resolveProfileKey();
        final String monitorKey = DisplayProfileStore.resolveMonitorAlias(
                displayKey);
        mProfileDisplayKey = displayKey;
        mMonitorProfileKey = monitorKey;
        final Display profileDisplay = getProfileDisplay();
        mProfile = DisplayProfileStore.load(
                monitorKey,
                initialDpi(profileDisplay));
        mActivity.onDisplayProfileReset();
        return mProfile;
    }

    void save() {
        DisplayProfileStore.save(getProfile());
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
        mMonitorIdentityGeneration++;
        mHandler.removeCallbacks(mMonitorIdentityRetry);
        getProfile();
    }

    void reloadStoredProfile() {
        mProfile = null;
        mMonitorProfileKey = null;
        getProfile();
    }

    String getMonitorLabel() {
        final Display display = getProfileDisplay();
        return display == null
                ? mActivity.getString(R.string.profile_default)
                : display.getName();
    }

    int getRecommendedDpi() {
        return initialDpi(getProfileDisplay());
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
        final int profileDisplayId = profileDisplay.getDisplayId();
        if (!reserveMonitorIdentityAttempt(profileDisplayId)) {
            return;
        }
        mMonitorIdentityRequested = true;
        mMonitorIdentityDisplayId = profileDisplayId;
        final String requestedDisplayKey = resolveProfileKey();
        final int generation = ++mMonitorIdentityGeneration;
        new Thread(() -> {
            final String output;
            try {
                output = readConnectedEdidHashes();
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

    static Integer prepareExternalProfile(
            final Context context, final int physicalDisplayId)
            throws IOException {
        if (context == null || physicalDisplayId <= 0) {
            return null;
        }
        final DisplayManager manager =
                context.getSystemService(DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(physicalDisplayId);
        if (display == null) {
            return null;
        }
        final String hash =
                parseSingleConnectedEdidHash(readConnectedEdidHashes());
        if (hash == null) {
            return Integer.valueOf(initialDpi(display));
        }
        final String monitorKey =
                "edid:" + hash.toLowerCase(Locale.ROOT);
        DisplayProfileStore.saveMonitorAlias(
                profileKey(display), monitorKey);
        final Integer storedDpi =
                DisplayProfileStore.readStoredDpi(monitorKey);
        return storedDpi == null
                ? Integer.valueOf(initialDpi(display)) : storedDpi;
    }

    private static String readConnectedEdidHashes() throws IOException {
        return ShellAccess.run(
                "for d in /sys/class/drm/card*-DP-*; do "
                        + "[ -d \"$d\" ] || continue; "
                        + "[ \"$(/system/bin/cat \"$d/status\" 2>/dev/null)\" "
                        + "= connected ] || continue; "
                        + "h=$(/system/bin/sha256sum \"$d/edid\" 2>/dev/null) "
                        + "|| continue; h=${h%% *}; "
                        + "printf '%s %s\\n' \"$h\" \"$d\"; done")
                .trim();
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
        return profileKey(profileDisplay);
    }

    private static String profileKey(final Display profileDisplay) {
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
        if (hasMonitorIdentityAttemptsRemaining(mMonitorIdentityDisplayId)) {
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
        final DisplayProfileStore.Profile previous = getProfile();
        final boolean existed =
                DisplayProfileStore.exists(monitorKey);
        final DisplayProfileStore.Profile resolved =
                DisplayProfileStore.load(
                        monitorKey,
                        previous.dpi);
        if (!existed) {
            resolved.dpiExplicit = previous.dpiExplicit;
            resolved.workspaceBounds.set(previous.workspaceBounds);
            resolved.workspaceBoundsTarget = previous.workspaceBoundsTarget;
            resolved.placements.putAll(previous.placements);
            DisplayProfileStore.save(resolved);
        }
        final int previousDpi = previous.dpi;
        DisplayProfileStore.saveMonitorAlias(
                requestedDisplayKey, monitorKey);
        mMonitorProfileKey = monitorKey;
        mProfile = resolved;
        Log.i(TAG, "Activated monitor profile " + monitorKey);
        mActivity.onMonitorProfileResolved(previousDpi, resolved.dpi);
    }

    private static int initialDpi(final Display display) {
        if (display == null
                || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return DesktopPreferences.SYSTEM_DESKTOP_DPI;
        }
        final Display.Mode mode = display.getMode();
        if (mode == null) {
            return DisplayDensityPolicy.recommendedExternalDpi(
                    0, 0, DisplayMetrics.DENSITY_DEVICE_STABLE);
        }
        return DisplayDensityPolicy.recommendedExternalDpi(
                mode.getPhysicalWidth(),
                mode.getPhysicalHeight(),
                DisplayMetrics.DENSITY_DEVICE_STABLE);
    }

    private static boolean reserveMonitorIdentityAttempt(final int displayId) {
        synchronized (MONITOR_IDENTITY_BUDGET_LOCK) {
            if (sMonitorIdentityDisplayId != displayId) {
                sMonitorIdentityDisplayId = displayId;
                sMonitorIdentityAttempts = 0;
            }
            if (sMonitorIdentityAttempts >= MAX_MONITOR_IDENTITY_ATTEMPTS) {
                return false;
            }
            sMonitorIdentityAttempts++;
            return true;
        }
    }

    private static boolean hasMonitorIdentityAttemptsRemaining(final int displayId) {
        synchronized (MONITOR_IDENTITY_BUDGET_LOCK) {
            return sMonitorIdentityDisplayId != displayId
                    || sMonitorIdentityAttempts < MAX_MONITOR_IDENTITY_ATTEMPTS;
        }
    }

    private static void resetMonitorIdentityBudget(final int displayId) {
        synchronized (MONITOR_IDENTITY_BUDGET_LOCK) {
            if (sMonitorIdentityDisplayId != displayId) {
                return;
            }
            sMonitorIdentityDisplayId = Display.INVALID_DISPLAY;
            sMonitorIdentityAttempts = 0;
        }
    }

}
