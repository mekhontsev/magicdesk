package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class UiMotionTest {
    @Test public void layoutStartsTheEffectAndCompletionRestoresExactProperties() throws Exception {
        verify("""
            view.x = 3; view.y = 4; view.sx = 1.2f; view.sy = 1.1f; view.alpha = .8f;
            UiMotion.reveal(view, true, ShellPanel.Edge.LEFT);
            check(ValueAnimator.latest == null && view.listeners.size() == 1, "started before layout");
            view.draw(); var animation = ValueAnimator.latest;
            near(view.x, -21); near(view.y, 4); near(view.sx, 1.08f); near(view.alpha, .28f);
            check(UiMotion.runningWithin(parent), "missing ancestor observation");
            animation.advance(.5f);
            near(view.x, -9); near(view.sx, 1.14f);
            animation.end();
            near(view.x, 3); near(view.y, 4); near(view.sx, 1.2f); near(view.sy, 1.1f); near(view.alpha, .8f);
            check(!UiMotion.runningWithin(parent) && view.listeners.isEmpty() && view.observer.listeners.isEmpty(), "leaked lifetime");
            check(parent.x == 0 && parent.y == 0 && parent.sx == 1, "moved input host");
            """);
    }

    @Test public void replacementDetachAndThemeChangesCancelWithoutStaleCallbacks() throws Exception {
        verify("""
            UiMotion.reveal(view, true, ShellPanel.Edge.TOP); view.draw();
            var first = ValueAnimator.latest;
            UiMotion.reveal(view, true, ShellPanel.Edge.RIGHT); view.draw();
            var second = ValueAnimator.latest;
            first.advance(1); first.end();
            check(UiMotion.runningWithin(view) && view.x > 0 && view.y == 0, "stale animation changed replacement");
            view.detach(); second.advance(.8f); second.end();
            near(view.x, 0); near(view.y, 0); near(view.sx, 1); near(view.alpha, 1);
            check(view.listeners.isEmpty(), "detach listener retained");
            UiMotion.reveal(view, true, null);
            check(view.observer.listeners.isEmpty(), "registered draw on detached view");
            view.attach(); check(view.observer.listeners.size() == 1, "lost attachment");
            AppearanceStore.theme = new ShellAppearance(new ShellMotion()); UiMotion.refresh();
            view.draw(); check(!UiMotion.runningWithin(view) && view.listeners.isEmpty(), "theme retained pending effect");
            """);
    }

    @Test public void disabledAnimationsRawWindowRootsAndEmptyLayoutsStayUntransformed() throws Exception {
        verify("""
            ValueAnimator.enabled = false;
            UiMotion.reveal(view, true, null); check(view.listeners.isEmpty(), "system disabled");
            ValueAnimator.enabled = true; view.parent = null;
            UiMotion.reveal(view, true, null); check(view.listeners.isEmpty(), "animated raw root");
            view.parent = parent; view.width = 0;
            UiMotion.reveal(view, true, null); view.draw();
            check(view.listeners.isEmpty() && !UiMotion.runningWithin(view), "empty layout retained animation");
            view.width = 200;
            UiMotion.reveal(view, true, null); view.draw();
            ValueAnimator.enabled = false; UiMotion.refresh();
            near(view.alpha, 1); near(view.y, 0); near(view.sy, 1);
            """);
    }

    @Test public void directionAmplitudeAndEffectSelectionAreBounded() throws Exception {
        verify("""
            view.height = 20;
            UiMotion.reveal(view, true, ShellPanel.Edge.BOTTOM); view.draw(); near(view.y, 10);
            UiMotion.cancel(view);
            AppearanceStore.theme.motion.effect = ShellMotion.Effect.FADE;
            UiMotion.reveal(view, false, ShellPanel.Edge.LEFT); view.draw(); near(view.x, 0); near(view.sx, 1);
            UiMotion.cancel(view);
            AppearanceStore.theme.motion.effect = ShellMotion.Effect.SCALE;
            UiMotion.reveal(view, true, null); view.draw(); near(view.y, 0); near(view.sx, .9f);
            UiMotion.cancel(view);
            AppearanceStore.theme.motion.effect = ShellMotion.Effect.SLIDE;
            UiMotion.reveal(view, true, ShellPanel.Edge.TOP); view.draw(); near(view.y, -10); near(view.sy, 1);
            UiMotion.cancel(view);
            """);
    }

    private static void verify(String body) throws Exception {
        RuntimeSourceFixture.verify(STUBS + "\nstatic " + RuntimeSourceFixture.nestedClass("UiMotion", "UiMotion")
                + "\npublic static void verify() { var parent = new ViewGroup(); var view = new View(); view.parent = parent;\n"
                + body + "\n}");
    }
    private static final String STUBS = """
        static void near(float actual, float expected) { check(Math.abs(actual - expected) < .0001f, actual + " != " + expected); }
        static class ShellPanel { enum Edge { TOP, BOTTOM, LEFT, RIGHT } }
        static class ShellMotion {
            enum Curve { LINEAR, EASE_OUT, SMOOTH }
            enum Effect { NONE, FADE, SLIDE, SCALE, SLIDE_SCALE;
                boolean slides() { return this == SLIDE || this == SLIDE_SCALE; }
                boolean scales() { return this == SCALE || this == SLIDE_SCALE; }
            }
            Effect effect = Effect.SLIDE_SCALE;
            Effect panels() { return effect; } Effect taskbar() { return effect; }
            int duration(Effect e, boolean enabled) { return enabled && e != Effect.NONE ? 160 : 0; }
            Curve curve() { return Curve.LINEAR; } int distanceDp() { return 12; } float scaleFrom() { return .9f; }
        }
        record ShellAppearance(ShellMotion motion) { }
        static class AppearanceStore {
            static ShellAppearance theme = new ShellAppearance(new ShellMotion());
            static ShellAppearance current(Object ignored) { return theme; }
        }
        static class Resources {
            static class Metrics { float density = 2; }
            Metrics getDisplayMetrics() { return new Metrics(); }
        }
        static class ViewTreeObserver {
            interface OnPreDrawListener { boolean onPreDraw(); }
            List<OnPreDrawListener> listeners = new ArrayList<>();
            boolean isAlive() { return true; }
            void addOnPreDrawListener(OnPreDrawListener l) { listeners.add(l); }
            void removeOnPreDrawListener(OnPreDrawListener l) { listeners.remove(l); }
        }
        static class View {
            interface OnAttachStateChangeListener { void onViewAttachedToWindow(View v); void onViewDetachedFromWindow(View v); }
            List<OnAttachStateChangeListener> listeners = new ArrayList<>();
            ViewTreeObserver observer = new ViewTreeObserver();
            boolean attached = true;
            Object parent;
            float alpha = 1, x, y, sx = 1, sy = 1;
            int width = 200, height = 100;
            Object getContext() { return this; } Object getParent() { return parent; }
            Resources getResources() { return new Resources(); }
            float getAlpha() { return alpha; } void setAlpha(float v) { alpha = v; }
            float getTranslationX() { return x; } float getTranslationY() { return y; }
            void setTranslationX(float v) { x = v; } void setTranslationY(float v) { y = v; }
            float getScaleX() { return sx; } float getScaleY() { return sy; }
            void setScaleX(float v) { sx = v; } void setScaleY(float v) { sy = v; }
            int getWidth() { return width; } int getHeight() { return height; }
            boolean isAttachedToWindow() { return attached; }
            void addOnAttachStateChangeListener(OnAttachStateChangeListener l) { listeners.add(l); }
            void removeOnAttachStateChangeListener(OnAttachStateChangeListener l) { listeners.remove(l); }
            ViewTreeObserver getViewTreeObserver() { return observer; }
            void invalidate() { }
            void draw() { for (var l : List.copyOf(observer.listeners)) l.onPreDraw(); }
            void detach() { attached = false; for (var l : List.copyOf(listeners)) l.onViewDetachedFromWindow(this); }
            void attach() { attached = true; for (var l : List.copyOf(listeners)) l.onViewAttachedToWindow(this); }
        }
        static class ViewGroup extends View { }
        static class Animator { }
        static class AnimatorListenerAdapter { public void onAnimationEnd(Animator a) { } }
        static class ValueAnimator extends Animator {
            interface UpdateListener { void onAnimationUpdate(ValueAnimator a); }
            static boolean enabled = true;
            static ValueAnimator latest;
            List<UpdateListener> updates = new ArrayList<>();
            List<AnimatorListenerAdapter> ends = new ArrayList<>();
            float fraction;
            static boolean areAnimatorsEnabled() { return enabled; }
            static ValueAnimator ofFloat(float a, float b) { return latest = new ValueAnimator(); }
            void setDuration(long ms) { } void setInterpolator(Object interpolator) { }
            void addUpdateListener(UpdateListener l) { updates.add(l); }
            void addListener(AnimatorListenerAdapter l) { ends.add(l); }
            void removeAllListeners() { ends.clear(); } void removeAllUpdateListeners() { updates.clear(); }
            Object getAnimatedValue() { return fraction; }
            void start() { } void cancel() { end(); }
            void advance(float value) { fraction = value; for (var l : List.copyOf(updates)) l.onAnimationUpdate(this); }
            void end() { for (var l : List.copyOf(ends)) l.onAnimationEnd(this); }
        }
        static class android { static class animation { interface TimeInterpolator { } } static class view { static class animation {
            static class LinearInterpolator implements android.animation.TimeInterpolator { }
            static class DecelerateInterpolator implements android.animation.TimeInterpolator { }
            static class AccelerateDecelerateInterpolator implements android.animation.TimeInterpolator { }
        } } }
        """;
}
