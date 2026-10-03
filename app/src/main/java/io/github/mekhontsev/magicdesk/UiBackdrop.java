package io.github.mekhontsev.magicdesk;

import android.view.View;
import android.view.Window;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Background-only styling. Android owns blur availability and its attachment lifecycle. */
final class UiBackdrop {
    private static final WeakHashMap<Binding, Boolean> BINDINGS = new WeakHashMap<>();

    static void bind(Window window, UiAppearance.Paint paint) {
        final View view = window.getDecorView();
        if (view.getTag(R.id.appearance_backdrop) instanceof Binding previous) previous.close();
        paint.backdrop = true;
        paint.refresh();
        window.setBackgroundDrawable(paint);
        final Binding binding = new Binding(window, paint);
        view.setTag(R.id.appearance_backdrop, binding);
        view.addOnAttachStateChangeListener(binding);
        BINDINGS.put(binding, true);
        if (view.isAttachedToWindow()) binding.refresh();
    }

    static void presented(Window window, boolean presented) {
        if (window.getDecorView().getTag(R.id.appearance_backdrop) instanceof Binding binding) {
            if (binding.presented == presented) return;
            binding.presented = presented;
            binding.refresh();
        }
    }

    static void unbind(Window window) {
        final View view = window.getDecorView();
        if (view.getTag(R.id.appearance_backdrop) instanceof Binding binding) binding.close();
        view.setTag(R.id.appearance_backdrop, null);
    }

    static void allowWindowBlur(Window window, boolean allowed) {
        if (window.getDecorView().getTag(R.id.appearance_backdrop) instanceof Binding binding) {
            if (binding.windowBlurAllowed == allowed) return;
            binding.windowBlurAllowed = allowed;
            binding.refresh();
        }
    }

    static void refreshAll() {
        for (Binding binding : new ArrayList<>(BINDINGS.keySet())) binding.refresh();
    }

    static int radiusPixels(int radiusDp, float density) {
        return Math.min(150, Math.max(0, Math.round(radiusDp * density)));
    }

    private static final class Binding implements View.OnAttachStateChangeListener {
        final Window window;
        final View view;
        final UiAppearance.Paint paint;
        boolean presented = true;
        boolean windowBlurAllowed = true;

        Binding(Window window, UiAppearance.Paint paint) {
            this.window = window; this.view = window.getDecorView(); this.paint = paint;
        }

        void refresh() {
            if (!view.isAttachedToWindow()) return;
            radius(presented && windowBlurAllowed && view.isHardwareAccelerated()
                    ? radiusPixels(paint.backdropStyle().blurRadiusDp(), paint.density) : 0);
        }

        private void radius(int value) {
            try { window.setBackgroundBlurRadius(value); }
            catch (RuntimeException unavailable) { /* Optional system effect leaves the translucent fill intact. */ }
        }

        @Override public void onViewAttachedToWindow(View ignored) { refresh(); }
        @Override public void onViewDetachedFromWindow(View ignored) { radius(0); }

        void close() {
            radius(0);
            view.removeOnAttachStateChangeListener(this);
            BINDINGS.remove(this);
        }
    }
}
