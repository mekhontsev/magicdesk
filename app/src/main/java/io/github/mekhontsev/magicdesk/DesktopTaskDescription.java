package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** App-owned task metadata, resolved from the same live appearance as its native UI. */
final class DesktopTaskDescription {
    private static final WeakHashMap<Activity, State> TASKS = new WeakHashMap<>();

    private record Identity(String label, int resource, Bitmap bitmap) { }
    private record Presentation(Identity identity, int primary, int background) { }
    private static final class State {
        Identity identity;
        Presentation published;
    }

    private DesktopTaskDescription() {
    }

    static void apply(
            final Activity activity,
            final int labelResId,
            final int iconResId) {
        final String label = activity.getString(labelResId);
        apply(activity, label, iconResId);
    }

    static void apply(final Activity activity, final String label, final int iconResId) {
        bind(activity, new Identity(label, iconResId, null));
    }

    static void apply(final Activity activity, final String label, final Bitmap icon) {
        bind(activity, new Identity(label, 0, icon));
    }

    private static void bind(Activity activity, Identity identity) {
        State state = TASKS.get(activity);
        if (state == null) {
            state = new State();
            TASKS.put(activity, state);
            View decor = activity.getWindow().getDecorView();
            decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View view) { refresh(activity); }
                public void onViewDetachedFromWindow(View view) { }
            });
            // A display move can change the inherited workspace palette without a theme edit.
            decor.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> refresh(activity));
        }
        state.identity = identity;
        refresh(activity);
    }

    static void refresh() {
        for (Activity activity : new ArrayList<>(TASKS.keySet())) refresh(activity);
    }

    private static void refresh(Activity activity) {
        State state = TASKS.get(activity);
        if (state == null) return;
        if (activity.isDestroyed() || activity.isFinishing()) {
            TASKS.remove(activity);
            return;
        }
        ShellAppearance.Palette palette = AppearanceStore.resolved(activity).theme().palette();
        int primary = palette.color(UiColor.PANEL);
        int background = palette.color(UiColor.BACKGROUND);
        if (state.published != null && state.identity.equals(state.published.identity())
                && primary == state.published.primary() && background == state.published.background()) return;
        Presentation next = new Presentation(state.identity, primary, background);
        FrameworkTaskDescriptionApi.publish(activity, next.identity().label(),
                next.identity().resource(), next.identity().bitmap(), next.primary(), next.background());
        state.published = next;
    }
}
