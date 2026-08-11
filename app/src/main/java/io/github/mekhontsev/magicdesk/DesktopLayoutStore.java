package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class DesktopLayoutStore {
    interface Mutation {
        void apply(Map<String, GlobalDesktopPlacement> placements);
    }

    private DesktopLayoutStore() {
    }

    static Map<String, GlobalDesktopPlacement> snapshot() {
        return DesktopStateStore.read(
                state -> new LinkedHashMap<>(state.desktopPlacements),
                Collections.emptyMap());
    }

    static boolean update(final Mutation mutation) {
        if (mutation == null) {
            return false;
        }
        return DesktopStateStore.update(
                state -> mutation.apply(state.desktopPlacements));
    }

    static boolean remove(final String itemId) {
        return itemId != null && update(
                placements -> placements.remove(itemId));
    }

    static boolean rename(
            final String previousItemId,
            final String newItemId) {
        if (previousItemId == null || previousItemId.isEmpty()
                || newItemId == null || newItemId.isEmpty()) {
            return false;
        }
        return update(placements -> {
            final GlobalDesktopPlacement placement =
                    placements.remove(previousItemId);
            if (placement != null) {
                placements.put(newItemId, placement);
            }
        });
    }
}
