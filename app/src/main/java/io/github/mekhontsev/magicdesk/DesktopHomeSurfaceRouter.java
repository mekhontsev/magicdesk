package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.util.Arrays;

/** Exposes primary and secondary HOME surfaces only for their session. */
final class DesktopHomeSurfaceRouter {
    enum Surface {
        PHONE,
        DESKTOP
    }

    private DesktopHomeSurfaceRouter() {
    }

    static Surface forTarget(final DesktopDisplayTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("HOME target is required");
        }
        return target.isPhoneWorkspace()
                ? Surface.DESKTOP : Surface.PHONE;
    }

    static void select(final Surface surface) throws IOException {
        if (surface == null) {
            throw new IllegalArgumentException("HOME surface is required");
        }
        apply(
                surface == Surface.PHONE
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                surface == Surface.DESKTOP
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
    }

    static void disableHomeSurfaces() throws IOException {
        apply(
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
    }

    private static void apply(
            final int phoneState,
            final int desktopState) throws IOException {
        final Context context = MagicDeskApplication.applicationContext();
        final PackageManager manager = context.getPackageManager();
        final ComponentName phone = new ComponentName(
                context, PhoneHomeActivity.class);
        final ComponentName desktop = new ComponentName(
                context, PhoneDesktopHomeActivity.class);
        final ComponentName secondary = new ComponentName(
                context, DesktopActivity.class);
        // External sessions use phone HOME and secondary HOME together. Both
        // must disappear from Android's launcher choices when the lease ends.
        if (manager.getComponentEnabledSetting(phone) == phoneState
                && manager.getComponentEnabledSetting(desktop)
                        == desktopState
                && manager.getComponentEnabledSetting(secondary) == phoneState) {
            return;
        }
        try {
            manager.setComponentEnabledSettings(Arrays.asList(
                    new PackageManager.ComponentEnabledSetting(
                            phone,
                            phoneState,
                            PackageManager.DONT_KILL_APP),
                    new PackageManager.ComponentEnabledSetting(
                            desktop,
                            desktopState,
                            PackageManager.DONT_KILL_APP),
                    new PackageManager.ComponentEnabledSetting(
                            secondary,
                            phoneState,
                            PackageManager.DONT_KILL_APP)));
        } catch (RuntimeException error) {
            throw new IOException(
                    "could not select HOME surfaces", error);
        }
    }
}
