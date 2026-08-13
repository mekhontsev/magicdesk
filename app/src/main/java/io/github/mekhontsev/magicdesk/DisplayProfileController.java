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

    private final Context mContext;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = this::refreshAfterDisplayChange;

    private DisplayManager.DisplayListener mDisplayListener;
    private DisplayProfileStore.Profile mProfile;
    private String mProfileDisplayKey;

    DisplayProfileController(final Context context, final Host host) {
        mContext = context;
        mHost = host;
    }

    void start() {
        final DisplayManager displayManager =
                mContext.getSystemService(DisplayManager.class);
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
                mContext.getSystemService(DisplayManager.class);
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
        mProfileDisplayKey = displayKey;
        final Display profileDisplay = getProfileDisplay();
        mProfile = DisplayProfileStore.load(
                displayKey,
                initialDpi(profileDisplay));
        mHost.onDisplayProfileReset();
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
        getProfile();
    }

    void reloadStoredProfile() {
        mProfile = null;
        getProfile();
    }

    String getDisplayLabel() {
        final Display display = getProfileDisplay();
        return display == null
                ? mContext.getString(R.string.profile_default)
                : display.getName();
    }

    int getRecommendedDpi() {
        return initialDpi(getProfileDisplay());
    }

    static DisplayProfileStore.Profile prepareExternalProfile(
            final Context context, final int physicalDisplayId) {
        if (context == null || physicalDisplayId <= 0) {
            return null;
        }
        return loadPreparedProfile(
                context,
                prepareTarget(
                        context,
                        DesktopDisplayTarget.wired(physicalDisplayId)));
    }

    static DesktopDisplayTarget prepareTarget(
            final Context context,
            final DesktopDisplayTarget target) {
        if (target == null
                || target.kind == DesktopDisplayTarget.Kind.PHONE
                || target.hasProfile()) {
            return target;
        }
        final DisplayManager manager = context == null
                ? null : context.getSystemService(DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(target.profileDisplayId);
        if (display == null) {
            return target;
        }
        return target.withProfile(
                target.profileDisplayId,
                stableProfileKey(
                        target.kind,
                        readDisplayUniqueId(target.profileDisplayId),
                        display.getName(),
                        display.getMode()));
    }

    static DisplayProfileStore.Profile loadPreparedProfile(
            final Context context,
            final DesktopDisplayTarget target) {
        if (context == null || target == null || !target.hasProfile()) {
            return null;
        }
        final DisplayManager manager =
                context.getSystemService(DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(target.profileDisplayId);
        if (display == null) {
            return null;
        }
        return DisplayProfileStore.load(
                target.profileKey, initialDpi(display));
    }

    private void refreshAfterDisplayChange() {
        if (mHost.isActivityUnavailable()) {
            return;
        }
        refreshForDisplay();
    }

    void scheduleRefresh() {
        mHandler.removeCallbacks(mRefresh);
        mHandler.post(mRefresh);
        mHandler.postDelayed(mRefresh, DISPLAY_SETTLE_MILLIS);
    }

    private String resolveProfileKey() {
        final String explicitProfileKey = mHost.getDesktopProfileKey();
        if (!explicitProfileKey.isEmpty()) {
            return explicitProfileKey;
        }
        final Display profileDisplay = getProfileDisplay();
        if (profileDisplay == null) {
            return "display:default";
        }
        return profileKey(profileDisplay);
    }

    private static String profileKey(final Display profileDisplay) {
        return stableProfileKey(
                null,
                readDisplayUniqueId(profileDisplay.getDisplayId()),
                profileDisplay.getName(),
                profileDisplay.getMode());
    }

    static String stableProfileKey(
            final DesktopDisplayTarget.Kind kind,
            final String uniqueId,
            final String name,
            final Display.Mode mode) {
        final String scope = kind == null
                ? "local" : kind.name().toLowerCase(Locale.ROOT);
        if (uniqueId != null && !uniqueId.trim().isEmpty()) {
            return "display:" + scope + ":" + uniqueId.trim();
        }
        final String resolution = mode == null
                ? "unknown"
                : mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight();
        final String displayName = name == null || name.trim().isEmpty()
                ? "unknown" : name.trim();
        return "display:" + scope + ":" + displayName + "|" + resolution;
    }

    private static String readDisplayUniqueId(final int displayId) {
        try {
            return ConsoleDisplayController.getDisplayUniqueId(displayId);
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Cannot read stable display id=" + displayId, error);
            return "";
        }
    }

    private Display getProfileDisplay() {
        final DisplayManager manager =
                mContext.getSystemService(DisplayManager.class);
        final Display current = mHost.getDisplay();
        if (manager == null) {
            return current;
        }
        final int profileDisplayId = mHost.getDesktopProfileDisplayId();
        if (profileDisplayId > Display.DEFAULT_DISPLAY) {
            final Display profileDisplay = manager.getDisplay(profileDisplayId);
            if (profileDisplay != null) {
                return profileDisplay;
            }
        }
        return current;
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

    interface Host {
        boolean isActivityUnavailable();

        Display getDisplay();

        int getDesktopProfileDisplayId();

        String getDesktopProfileKey();

        void onDisplayProfileReset();

    }

}
