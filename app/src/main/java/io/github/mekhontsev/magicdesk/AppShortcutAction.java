package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One launcher action published by an installed application. */
final class AppShortcutAction {
    final String id;
    final String label;
    final Drawable icon;

    private final Intent mIntent;

    AppShortcutAction(
            final String id,
            final String label,
            final Drawable icon,
            final Intent intent) {
        if (id == null || id.isEmpty()
                || label == null || label.isEmpty()
                || intent == null
                || intent.getComponent() == null) {
            throw new IllegalArgumentException("invalid app shortcut action");
        }
        this.id = id;
        this.label = label;
        this.icon = icon;
        mIntent = new Intent(intent);
    }

    Intent launchIntent() {
        return new Intent(mIntent);
    }
}
