package io.github.mekhontsev.magicdesk;

import android.graphics.Insets;

/** Retains a known caption during host replacement until WM publishes it, not for a timed interval. */
final class CaptionInsetsHandoff {
    private Insets pending;

    CaptionInsetsHandoff(Insets previous) { pending = previous; }

    Insets resolve(Insets published, boolean multiWindow) {
        if (!multiWindow || !Insets.NONE.equals(published)) pending = Insets.NONE;
        return pending;
    }
}
