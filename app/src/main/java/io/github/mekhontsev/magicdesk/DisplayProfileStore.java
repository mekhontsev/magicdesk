package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.util.LinkedHashMap;
import java.util.Map;

final class DisplayProfileStore {
    private DisplayProfileStore() {
    }

    static Profile load(final String monitorKey,
            final int defaultDpi) {
        final Profile stored = DesktopStateStore.read(state ->
                copy(state.displayProfiles.get(monitorKey)), null);
        if (stored != null) {
            return stored;
        }

        final Profile profile = new Profile(monitorKey);
        profile.dpi = defaultDpi;
        profile.dpiExplicit = false;
        save(profile);
        return profile;
    }

    static boolean save(final Profile profile) {
        if (profile == null || profile.monitorKey == null
                || profile.monitorKey.length() == 0) {
            return false;
        }
        final Profile snapshot = copy(profile);
        return DesktopStateStore.update(state ->
                state.displayProfiles.put(snapshot.monitorKey, snapshot));
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
        DesktopStateStore.update(state -> {
            for (final Profile profile : state.displayProfiles.values()) {
                final DesktopPlacement placement =
                        profile.placements.remove(previousItemId);
                if (placement != null && newItemId != null) {
                    profile.placements.put(newItemId, placement);
                }
            }
        });
    }

    static boolean exists(final String monitorKey) {
        return DesktopStateStore.read(
                state -> state.displayProfiles.containsKey(monitorKey),
                false);
    }

    static Integer readStoredDpi(
            final String monitorKey) {
        final Profile profile = DesktopStateStore.read(
                state -> state.displayProfiles.get(monitorKey), null);
        return profile == null ? null : Integer.valueOf(profile.dpi);
    }

    static String resolveMonitorAlias(final String displayKey) {
        if (displayKey == null || displayKey.length() == 0) {
            return displayKey;
        }
        final String monitorKey = DesktopStateStore.read(
                state -> state.displayAliases.get(displayKey), null);
        return monitorKey == null ? displayKey : monitorKey;
    }

    static void saveMonitorAlias(final String displayKey,
            final String monitorKey) {
        if (displayKey == null || displayKey.length() == 0
                || monitorKey == null || monitorKey.length() == 0) {
            return;
        }
        DesktopStateStore.update(state ->
                state.displayAliases.put(displayKey, monitorKey));
    }

    static Profile copy(final Profile source) {
        if (source == null) {
            return null;
        }
        final Profile copy = new Profile(source.monitorKey);
        copy.dpi = source.dpi;
        copy.dpiExplicit = source.dpiExplicit;
        copy.workspaceBounds.left = source.workspaceBounds.left;
        copy.workspaceBounds.top = source.workspaceBounds.top;
        copy.workspaceBounds.right = source.workspaceBounds.right;
        copy.workspaceBounds.bottom = source.workspaceBounds.bottom;
        copy.workspaceBoundsTarget = source.workspaceBoundsTarget;
        copy.placements.putAll(source.placements);
        return copy;
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
