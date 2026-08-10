package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.util.LinkedHashMap;
import java.util.Map;

final class DisplayProfileStore {
    private DisplayProfileStore() {
    }

    static Profile load(final String key,
            final int defaultDpi) {
        final Profile stored = DesktopStateStore.read(state ->
                copy(state.displayProfiles.get(key)), null);
        if (stored != null) {
            return stored;
        }

        final Profile profile = new Profile(key);
        profile.dpi = defaultDpi;
        profile.dpiExplicit = false;
        return profile;
    }

    static boolean save(final Profile profile) {
        if (profile == null || profile.key == null
                || profile.key.length() == 0) {
            return false;
        }
        final Profile snapshot = copy(profile);
        return DesktopStateStore.update(state ->
                state.displayProfiles.put(snapshot.key, snapshot));
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

    static Profile copy(final Profile source) {
        if (source == null) {
            return null;
        }
        final Profile copy = new Profile(source.key);
        copy.dpi = source.dpi;
        copy.dpiExplicit = source.dpiExplicit;
        copy.fillDisplay = source.fillDisplay;
        copy.outputTiming = source.outputTiming;
        copy.workspaceBounds.left = source.workspaceBounds.left;
        copy.workspaceBounds.top = source.workspaceBounds.top;
        copy.workspaceBounds.right = source.workspaceBounds.right;
        copy.workspaceBounds.bottom = source.workspaceBounds.bottom;
        copy.workspaceBoundsTarget = source.workspaceBoundsTarget;
        copy.placements.putAll(source.placements);
        return copy;
    }

    static final class Profile {
        final String key;
        int dpi;
        boolean dpiExplicit;
        boolean fillDisplay = true;
        String outputTiming;
        Rect workspaceBounds = new Rect();
        String workspaceBoundsTarget;
        final Map<String, DesktopPlacement> placements =
                new LinkedHashMap<>();

        Profile(final String key) {
            this.key = key;
        }
    }
}
