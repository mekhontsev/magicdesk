package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Exposes primary and secondary HOME surfaces only for their session. */
final class DesktopHomeSurfaceRouter {
    enum Surface {
        SYSTEM,
        PHONE,
        DESKTOP
    }

    /** Component admission is session-wide; each HOME surface follows local residency. */
    static final class Selection {
        private final Set<Integer> workspaces;
        final Surface primary;
        final boolean secondaryDesktop;

        private Selection(final Set<Integer> displays) {
            workspaces = Collections.unmodifiableSet(displays);
            primary = surfaceOn(0);
            secondaryDesktop = displays.stream().anyMatch(id -> id != 0);
        }

        Surface surfaceOn(final int displayId) {
            if (displayId < 0) {
                throw new IllegalArgumentException("HOME display is required");
            }
            if (workspaces.contains(displayId)) {
                return Surface.DESKTOP;
            }
            return displayId == 0 && !workspaces.isEmpty() ? Surface.PHONE : Surface.SYSTEM;
        }
    }

    private DesktopHomeSurfaceRouter() {
    }

    static Selection forWorkspaces(final Iterable<DesktopDisplayTarget> targets) {
        final Set<Integer> displays = new HashSet<>();
        for (final DesktopDisplayTarget target : targets) {
            if (target == null || !displays.add(target.workspaceDisplayId)) {
                throw new IllegalArgumentException("distinct HOME workspaces are required");
            }
        }
        return new Selection(displays);
    }

    static void select(final Selection selection) throws IOException {
        if (selection == null) {
            throw new IllegalArgumentException("HOME surface is required");
        }
        apply(
                selection.primary == Surface.PHONE
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                selection.primary == Surface.DESKTOP
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                selection.secondaryDesktop
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
    }

    static void disableHomeSurfaces() throws IOException {
        apply(
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
    }

    private static void apply(
            final int phoneState,
            final int desktopState,
            final int secondaryState) throws IOException {
        final Context context = MagicDeskApplication.applicationContext();
        final PackageManager manager = context.getPackageManager();
        final ComponentName phone = new ComponentName(
                context, PhoneHomeActivity.class);
        final ComponentName desktop = new ComponentName(
                context, PhoneDesktopHomeActivity.class);
        final ComponentName secondary = new ComponentName(
                context, DesktopActivity.class);
        // Primary and secondary hosts are independent. All must disappear
        // from Android's launcher choices when the shared HOME lease ends.
        if (manager.getComponentEnabledSetting(phone) == phoneState
                && manager.getComponentEnabledSetting(desktop)
                        == desktopState
                && manager.getComponentEnabledSetting(secondary) == secondaryState) {
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
                            secondaryState,
                            PackageManager.DONT_KILL_APP)));
        } catch (RuntimeException error) {
            throw new IOException(
                    "could not select HOME surfaces", error);
        }
    }
}
