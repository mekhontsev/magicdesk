package io.github.mekhontsev.magicdesk;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.util.IdentityHashMap;

/** Animates child content, never the window frame, backdrop, focus or input ownership. */
final class UiMotion {
    static android.animation.TimeInterpolator interpolator(ShellMotion.Curve curve) {
        return switch (curve) {
            case LINEAR -> new android.view.animation.LinearInterpolator();
            case EASE_OUT -> new android.view.animation.DecelerateInterpolator();
            case SMOOTH -> new android.view.animation.AccelerateDecelerateInterpolator();
        };
    }
    private static final IdentityHashMap<View, Running> ACTIVE = new IdentityHashMap<>();

    static void reveal(View view, boolean panel, ShellPanel.Edge edge) {
        cancel(view);
        var theme = AppearanceStore.current(view.getContext());
        var motion = theme.motion();
        var effect = panel ? motion.panels() : motion.taskbar();
        int duration = motion.duration(effect, ValueAnimator.areAnimatorsEnabled());
        // A ViewGroup inverse-transforms pointer events for its child. ViewRoot is not that host.
        if (duration == 0 || !(view.getParent() instanceof ViewGroup)) return;
        var running = new Running(view, theme, effect, edge, duration);
        ACTIVE.put(view, running);
        view.addOnAttachStateChangeListener(running);
        if (view.isAttachedToWindow()) running.prepare();
    }
    static void cancel(View view) {
        var running = ACTIVE.get(view);
        if (running != null) running.finish();
    }
    static void refresh() {
        for (var running : java.util.List.copyOf(ACTIVE.values())) {
            if (!ValueAnimator.areAnimatorsEnabled()
                    || !AppearanceStore.current(running.view.getContext()).equals(running.theme)) running.finish();
        }
    }

    static boolean runningWithin(View root) {
        for (View view : ACTIVE.keySet()) {
            for (View current = view; current != null;
                    current = current.getParent() instanceof View parent ? parent : null) {
                if (current == root) return true;
            }
        }
        return false;
    }

    private static final class Running implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final View view;
        final ShellAppearance theme;
        final ShellMotion.Effect effect;
        final ShellPanel.Edge edge;
        final int duration;
        final float alpha, x, y, scaleX, scaleY;
        ValueAnimator animator;
        ViewTreeObserver pendingDraw;
        boolean finished;

        Running(View view, ShellAppearance theme, ShellMotion.Effect effect, ShellPanel.Edge edge, int duration) {
            this.view = view; this.theme = theme; this.effect = effect; this.edge = edge; this.duration = duration;
            alpha = view.getAlpha(); x = view.getTranslationX(); y = view.getTranslationY();
            scaleX = view.getScaleX(); scaleY = view.getScaleY();
        }

        void prepare() {
            if (finished || pendingDraw != null || animator != null) return;
            pendingDraw = view.getViewTreeObserver();
            pendingDraw.addOnPreDrawListener(this);
            view.invalidate();
        }

        @Override public boolean onPreDraw() {
            removeDraw();
            if (finished) return true;
            if (!ValueAnimator.areAnimatorsEnabled() || view.getWidth() <= 0 || view.getHeight() <= 0) {
                finish(); return true;
            }
            var motion = theme.motion();
            boolean horizontal = edge == ShellPanel.Edge.LEFT || edge == ShellPanel.Edge.RIGHT;
            float distance = effect.slides() ? Math.min(motion.distanceDp() * view.getResources().getDisplayMetrics().density,
                    (horizontal ? view.getWidth() : view.getHeight()) / 2f) : 0;
            float dx = horizontal ? (edge == ShellPanel.Edge.LEFT ? -distance : distance) : 0;
            float dy = horizontal ? 0 : (edge == ShellPanel.Edge.TOP ? -distance : distance);
            float initialScale = effect.scales() ? motion.scaleFrom() : 1;
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(duration);
            animator.setInterpolator(interpolator(motion.curve()));
            animator.addUpdateListener(value -> {
                if (!finished) apply((float) value.getAnimatedValue(), dx, dy, initialScale);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator ignored) { finish(); }
            });
            apply(0, dx, dy, initialScale);
            animator.start();
            return true;
        }

        void apply(float fraction, float dx, float dy, float initialScale) {
            float remaining = 1 - fraction;
            view.setAlpha(alpha * (1 - .65f * remaining));
            view.setTranslationX(x + dx * remaining); view.setTranslationY(y + dy * remaining);
            float scale = 1 - (1 - initialScale) * remaining;
            view.setScaleX(scaleX * scale); view.setScaleY(scaleY * scale);
        }

        void removeDraw() {
            if (pendingDraw != null && pendingDraw.isAlive()) pendingDraw.removeOnPreDrawListener(this);
            pendingDraw = null;
        }

        void finish() {
            if (finished) return;
            finished = true;
            ACTIVE.remove(view);
            removeDraw();
            view.removeOnAttachStateChangeListener(this);
            if (animator != null) { animator.removeAllUpdateListeners(); animator.removeAllListeners(); animator.cancel(); }
            view.setAlpha(alpha); view.setTranslationX(x); view.setTranslationY(y);
            view.setScaleX(scaleX); view.setScaleY(scaleY);
        }

        @Override public void onViewAttachedToWindow(View v) { prepare(); }
        @Override public void onViewDetachedFromWindow(View v) { finish(); }
    }
}
