package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowInsets;

/** Keeps full-screen activity content clear of phone system bars and cutouts. */
final class SystemBarInsets {
    private static final String CAPTION_HANDOFF = "magicdesk_caption_handoff";
    private SystemBarInsets() {
    }

    static void addToPadding(final View view) {
        addToPadding(view, false);
    }

    static void addToPadding(final View view, final boolean includeIme) {
        addToPadding(view, includeIme, null);
    }

    static void preserveCaption(Activity source, Intent replacement) {
        WindowInsets insets = source.getWindow().getDecorView().getRootWindowInsets();
        if (!source.isInMultiWindowMode() || insets == null) return;
        Insets caption = insets.getInsets(WindowInsets.Type.captionBar());
        if (!Insets.NONE.equals(caption)) replacement.putExtra(CAPTION_HANDOFF,
                new Rect(caption.left, caption.top, caption.right, caption.bottom));
    }

    static void addToPadding(final View view, final boolean includeIme, final Activity host) {
        Rect previous = host == null ? null
                : host.getIntent().getParcelableExtra(CAPTION_HANDOFF, Rect.class);
        final CaptionInsetsHandoff handoff = new CaptionInsetsHandoff(previous == null ? Insets.NONE
                : Insets.of(Math.max(0, previous.left), Math.max(0, previous.top),
                        Math.max(0, previous.right), Math.max(0, previous.bottom)));
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            Insets safeArea = windowInsets.getInsets(
                    WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout()
                            | (includeIme ? WindowInsets.Type.ime() : 0));
            // DecorView may already have consumed the caption before dispatching to this child.
            WindowInsets published = target.getRootWindowInsets();
            safeArea = Insets.max(safeArea, handoff.resolve(published == null ? Insets.NONE
                            : published.getInsets(WindowInsets.Type.captionBar()),
                    host != null && host.isInMultiWindowMode()));
            target.setPadding(
                    left + safeArea.left,
                    top + safeArea.top,
                    right + safeArea.right,
                    bottom + safeArea.bottom);
            return windowInsets;
        });
    }
}
