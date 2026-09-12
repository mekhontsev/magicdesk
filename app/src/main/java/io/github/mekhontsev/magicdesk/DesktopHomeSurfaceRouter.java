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
        LAUNCHER,
        DESKTOP
    }

    /** Component admission is session-wide; each HOME surface follows local residency. */
    static final class Selection {
        private final Set<Integer> workspaces;
        final Surface primary;
        final boolean secondaryHome;

        private Selection(final Set<Integer> displays) {
            workspaces = Collections.unmodifiableSet(displays);
            primary = surfaceOn(0);
            secondaryHome = !displays.isEmpty();
        }

        Surface surfaceOn(final int displayId) {
            if (displayId < 0) {
                throw new IllegalArgumentException("HOME display is required");
            }
            if (workspaces.contains(displayId)) {
                return Surface.DESKTOP;
            }
            return !workspaces.isEmpty() ? Surface.LAUNCHER : Surface.SYSTEM;
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
                selection.primary != Surface.SYSTEM
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                selection.secondaryHome
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
            final int secondaryState) throws IOException {
        final Context context = MagicDeskApplication.applicationContext();
        final PackageManager manager = context.getPackageManager();
        final ComponentName phone = new ComponentName(
                context, PhoneHomeActivity.class);
        final ComponentName secondary = new ComponentName(
                context, DesktopActivity.class);
        // Keep HOME identities stable throughout the lease. Replacing the
        // primary component invalidates Android's preferred Activity while
        // its package can still hold ROLE_HOME, exposing a launcher chooser.
        if (manager.getComponentEnabledSetting(phone) == phoneState
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
                            secondary,
                            secondaryState,
                            PackageManager.DONT_KILL_APP)));
        } catch (RuntimeException error) {
            throw new IOException(
                    "could not select HOME surfaces", error);
        }
    }
}
