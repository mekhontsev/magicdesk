package io.github.mekhontsev.magicdesk;

import java.util.Set;

/** Privileged-process admission snapshot; readers never query application state. */
final class ShellWorkspaceMembership {
    private volatile Set<Integer> mDisplays = Set.of();

    void update(final Set<Integer> displays) {
        mDisplays = Set.copyOf(displays);
    }

    boolean hasPhoneDesktop() {
        return mDisplays.contains(0);
    }

    boolean ownsPhoneNormalization(final int displayId) {
        final Set<Integer> displays = mDisplays;
        return displayId > 0 && !displays.contains(0)
                && displays.stream().filter(id -> id > 0).min(Integer::compareTo)
                        .orElse(-1) == displayId;
    }
}
