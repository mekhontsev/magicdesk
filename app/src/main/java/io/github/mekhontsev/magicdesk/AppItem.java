package io.github.mekhontsev.magicdesk;

import android.graphics.drawable.Drawable;

final class AppItem {
    static final String FULLSCREEN_REASON_NONE = "none";
    static final String FULLSCREEN_REASON_IMMERSIVE = "immersive";
    static final String FULLSCREEN_REASON_UNRESIZEABLE = "unresizable";
    static final String FULLSCREEN_REASON_GAME = "game";

    final String label;
    final String packageName;
    final boolean canFloat;
    final String fullscreenReason;
    final Drawable icon;

    AppItem(
            final String label,
            final String packageName,
            final boolean canFloat,
            final String fullscreenReason,
            final Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.canFloat = canFloat;
        this.fullscreenReason = fullscreenReason;
        this.icon = icon;
    }
}
