package io.github.mekhontsev.magicdesk;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/** Keeps full-screen activity content clear of phone system bars and cutouts. */
final class SystemBarInsets {
    private SystemBarInsets() {
    }

    static void addToPadding(final View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            final Insets safeArea = windowInsets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
            target.setPadding(
                    left + safeArea.left,
                    top + safeArea.top,
                    right + safeArea.right,
                    bottom + safeArea.bottom);
            return windowInsets;
        });
    }
}
