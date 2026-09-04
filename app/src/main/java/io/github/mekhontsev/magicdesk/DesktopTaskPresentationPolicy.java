package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

/** Resolves display-independent app scale to an exact task density. */
final class DesktopTaskPresentationPolicy {
    private DesktopTaskPresentationPolicy() {
    }

    static int resolveDensityDpi(
            final String packageName,
            final int displayId) {
        final AppPresentationProfile profile =
                AppPresentationProfileStore.load(packageName);
        if (profile == null) {
            return DesktopTaskDensity.INHERIT;
        }
        final int displayDensityDpi = displayDensityDpi(displayId);
        return resolveDensityDpi(profile, displayDensityDpi);
    }

    static int resolveDensityDpi(
            final AppPresentationProfile profile,
            final int displayDensityDpi) {
        if (profile == null) {
            return DesktopTaskDensity.INHERIT;
        }
        if (displayDensityDpi <= 0) {
            throw new IllegalArgumentException(
                    "display density must be positive");
        }
        return DesktopTaskDensity.clamp(Math.round(
                displayDensityDpi * profile.scalePercent / 100f));
    }

    static int expectedDensityDpi(
            final AppPresentationProfile profile,
            final int displayDensityDpi) {
        return profile == null
                ? displayDensityDpi
                : resolveDensityDpi(profile, displayDensityDpi);
    }

    static int displayDensityDpi(final int displayId) {
        final Context context = MagicDeskApplication.applicationContext();
        if (context == null) {
            throw new IllegalStateException(
                    "application context is unavailable");
        }
        final DisplayManager manager =
                context.getSystemService(DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(displayId);
        if (display == null) {
            throw new IllegalArgumentException(
                    "display is unavailable: " + displayId);
        }
        final DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        if (metrics.densityDpi <= 0) {
            throw new IllegalStateException(
                    "display density is unavailable: " + displayId);
        }
        return metrics.densityDpi;
    }

    static int displayDensityDpi(final Context displayContext) {
        if (displayContext == null) {
            return -1;
        }
        final DisplayMetrics metrics =
                displayContext.getResources().getDisplayMetrics();
        return metrics == null ? -1 : metrics.densityDpi;
    }
}
