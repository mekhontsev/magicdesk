package io.github.mekhontsev.magicdesk;

import android.app.Dialog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/** Public Window decor for a native panel; its caller still owns placement and input. */
final class UiPanelWindow implements AutoCloseable {
    private final View content;
    private final UiAppearance.Paint paint;
    private final Window window;
    private final android.graphics.Rect insets = new android.graphics.Rect();

    UiPanelWindow(View content) {
        this.content = content;
        paint = (UiAppearance.Paint) content.getBackground();
        // Obtain framework decor without showing a dialog or installing dialog input policy.
        window = new Dialog(content.getContext(), R.style.DesktopChromeTheme).getWindow();
        window.setCallback(null);
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
        window.setDecorFitsSystemWindows(false);
        content.setBackground(null);
        window.setContentView(content);
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.getDecorView().setClipToOutline(true);
        // ViewGroup does not forward outside notifications to child touch targets.
        window.getDecorView().setOnTouchListener((view, event) ->
                event.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                        && content.dispatchTouchEvent(event));
        UiBackdrop.bind(window, paint);
    }

    View view() { return window.getDecorView(); }

    void attributes(WindowManager.LayoutParams params) { window.setAttributes(params); }

    void backdropInsets(int left, int top, int right, int bottom) {
        if (insets.left == left && insets.top == top && insets.right == right && insets.bottom == bottom) return;
        insets.set(left, top, right, bottom);
        boolean expanded = left != 0 || top != 0 || right != 0 || bottom != 0;
        window.setBackgroundDrawable(expanded ? new android.graphics.drawable.InsetDrawable(paint, left, top, right, bottom) {
            @Override public boolean getPadding(android.graphics.Rect padding) {
                // Paint insets must not become content padding when Android rebuilds its decor.
                padding.setEmpty(); return false;
            }
        } : paint);
        UiBackdrop.allowWindowBlur(window, !expanded);
        window.getDecorView().setClipToOutline(!expanded);
        for (View view = content; view != window.getDecorView() && view.getParent() instanceof ViewGroup parent; view = parent) {
            parent.setClipChildren(!expanded); parent.setClipToPadding(!expanded);
        }
        if (window.getDecorView() instanceof ViewGroup group) { group.setClipChildren(!expanded); group.setClipToPadding(!expanded); }
    }

    void presented(boolean visible) {
        paint.setAlpha(visible ? 255 : 0);
        UiBackdrop.presented(window, visible);
    }

    @Override public void close() {
        window.getDecorView().setOnTouchListener(null);
        UiBackdrop.unbind(window);
        if (content.getParent() instanceof ViewGroup parent) parent.removeView(content);
        content.setBackground(paint);
    }
}
