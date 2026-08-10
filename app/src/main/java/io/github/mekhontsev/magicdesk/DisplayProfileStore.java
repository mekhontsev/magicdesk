package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.util.LinkedHashMap;
import java.util.Map;

final class DisplayProfileStore {
    private DisplayProfileStore() {
    }

    static Profile load(final String monitorKey,
            final int defaultDpi) {
        final Profile stored = DesktopStateStore.get()
                .displayProfiles.get(monitorKey);
        if (stored != null) {
            return stored;
        }

        final Profile profile = new Profile(monitorKey);
        profile.dpi = defaultDpi;
        profile.dpiExplicit = false;
        save(profile);
        return profile;
    }

    static void save(final Profile profile) {
        if (profile == null || profile.monitorKey == null
                || profile.monitorKey.length() == 0) {
            return;
        }
        DesktopStateStore.get().displayProfiles.put(
                profile.monitorKey, profile);
        DesktopStateStore.save();
    }

    static void removePlacementEverywhere(
            final String itemId) {
        updatePlacementEverywhere(itemId, null);
    }

    static void renamePlacementEverywhere(
            final String previousItemId,
            final String newItemId) {
        if (newItemId == null || newItemId.length() == 0) {
            return;
        }
        updatePlacementEverywhere(previousItemId, newItemId);
    }

    private static void updatePlacementEverywhere(
            final String previousItemId,
            final String newItemId) {
        if (previousItemId == null || previousItemId.length() == 0) {
            return;
        }
        boolean changed = false;
        for (final Profile profile : DesktopStateStore.get()
                .displayProfiles.values()) {
            final DesktopPlacement placement =
                    profile.placements.remove(previousItemId);
            if (placement == null) {
                continue;
            }
            if (newItemId != null) {
                profile.placements.put(newItemId, placement);
            }
            changed = true;
        }
        if (changed) {
            DesktopStateStore.save();
        }
    }

    static boolean exists(final String monitorKey) {
        return DesktopStateStore.get().displayProfiles.containsKey(monitorKey);
    }

    static Integer readStoredDpi(
            final String monitorKey) {
        final Profile profile = DesktopStateStore.get()
                .displayProfiles.get(monitorKey);
        return profile == null ? null : Integer.valueOf(profile.dpi);
    }

    static String resolveMonitorAlias(final String displayKey) {
        if (displayKey == null || displayKey.length() == 0) {
            return displayKey;
        }
        final String monitorKey = DesktopStateStore.get()
                .displayAliases.get(displayKey);
        return monitorKey == null ? displayKey : monitorKey;
    }

    static void saveMonitorAlias(final String displayKey,
            final String monitorKey) {
        if (displayKey == null || displayKey.length() == 0
                || monitorKey == null || monitorKey.length() == 0) {
            return;
        }
        final String previous = DesktopStateStore.get()
                .displayAliases.put(displayKey, monitorKey);
        if (!monitorKey.equals(previous)) {
            DesktopStateStore.save();
        }
    }

    static final class Profile {
        final String monitorKey;
        int dpi;
        boolean dpiExplicit;
        Rect workspaceBounds = new Rect();
        String workspaceBoundsTarget;
        final Map<String, DesktopPlacement> placements =
                new LinkedHashMap<>();

        Profile(final String monitorKey) {
            this.monitorKey = monitorKey;
        }
    }
}
