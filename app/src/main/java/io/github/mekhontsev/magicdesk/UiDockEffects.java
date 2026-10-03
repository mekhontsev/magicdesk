package io.github.mekhontsev.magicdesk;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;

/** One panel's visual/input transforms; action Views and their listeners remain the owners. */
final class UiDockEffects {
    private final ViewGroup host;
    private final Runnable inputChanged;
    private final ArrayList<Item> items = new ArrayList<>();
    private final java.util.IdentityHashMap<ViewGroup, Clip> clips = new java.util.IdentityHashMap<>();
    private final Rect bounds = new Rect();
    private ShellDockEffect effect = ShellDockEffect.NONE;
    private ShellPanel.Edge edge = ShellPanel.Edge.BOTTOM;
    private ShellMotion motion = ShellMotion.defaults();
    private ValueAnimator animator;
    private Item hover, pressed;
    private float pointer = Float.NaN;
    private View root;

    UiDockEffects(ViewGroup host, Runnable inputChanged) { this.host = host; this.inputChanged = inputChanged; }

    void configure(ShellDockEffect effect, ShellPanel.Edge edge, ShellMotion motion) {
        if (this.effect.equals(effect) && this.edge == edge && this.motion.equals(motion)) return;
        reset(); root = null; this.effect = effect; this.edge = edge; this.motion = motion;
    }

    void layout(View root, boolean changed) {
        // Publishing a touch region requests layout too; it must not restart the hover animation.
        if (!changed && this.root == root && matchingItems(root, 0) == items.size()) {
            updatePositions(); return;
        }
        clear(); this.root = root;
        if (enabled()) collect(root);
    }

    private int matchingItems(View view, int index) {
        if (view.getVisibility() != View.VISIBLE) return index;
        if (Boolean.TRUE.equals(view.getTag(R.id.appearance_dock_item)))
            return index < items.size() && items.get(index).view == view ? index + 1 : -1;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount() && index >= 0; i++) index = matchingItems(group.getChildAt(i), index);
        return index;
    }

    private boolean enabled() { return effect.enabled() && !motion.reduced() && ValueAnimator.areAnimatorsEnabled(); }

    private void collect(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (Boolean.TRUE.equals(view.getTag(R.id.appearance_dock_item))) {
            float x = 0, y = 0;
            for (View current = view; current != host;) {
                x += current.getLeft(); y += current.getTop();
                if (!(current.getParent() instanceof ViewGroup parent)) return;
                x -= parent.getScrollX(); y -= parent.getScrollY();
                if (!clips.containsKey(parent)) {
                    clips.put(parent, new Clip(parent.getClipChildren(), parent.getClipToPadding(), parent.getClipBounds()));
                    parent.setClipChildren(false); parent.setClipToPadding(false);
                    int margin = effect.overflow(Math.max(view.getWidth(), view.getHeight()),
                            host.getResources().getDisplayMetrics().density);
                    if (parent instanceof android.widget.HorizontalScrollView)
                        parent.setClipBounds(new Rect(0, -margin, parent.getWidth(), parent.getHeight() + margin));
                    else if (parent instanceof android.widget.ScrollView)
                        parent.setClipBounds(new Rect(-margin, 0, parent.getWidth() + margin, parent.getHeight()));
                }
                current = parent;
            }
            items.add(new Item(view, x, y));
        } else if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i));
        }
    }

    boolean hover(MotionEvent event) {
        if (items.isEmpty()) return false;
        if (!enabled() || UiMotion.runningWithin(root)) { reset(); return false; }
        updatePositions();
        boolean exit = event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT;
        // Android ends hover before mouse DOWN. Keep its visual hit target through UP.
        if (exit && (pressed != null || event.getButtonState() != 0)) return hover != null;
        Item target = exit ? null : hit(event.getX(), event.getY());
        if (hover != target) {
            if (hover != null) deliver(hover, event, MotionEvent.ACTION_HOVER_EXIT, true);
            hover = target;
            if (hover != null) deliver(hover, event, MotionEvent.ACTION_HOVER_ENTER, true);
        }
        if (hover != null && !exit) deliver(hover, event, MotionEvent.ACTION_HOVER_MOVE, true);
        pointer = exit ? Float.NaN : edge.vertical() ? event.getY() : event.getX();
        animate();
        return target != null;
    }

    boolean touch(MotionEvent event) {
        if (!event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                || items.isEmpty() || !enabled() || UiMotion.runningWithin(root)) return false;
        updatePositions();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pressed = hit(event.getX(), event.getY());
            if (pressed != null) stopAnimation();
        }
        Item target = pressed;
        if (target == null) return false;
        deliver(target, event, action, false);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pressed = null;
        return true;
    }

    boolean generic(MotionEvent event) {
        int action = event.getActionMasked();
        if (items.isEmpty() || !enabled() || UiMotion.runningWithin(root)
                || action != MotionEvent.ACTION_BUTTON_PRESS && action != MotionEvent.ACTION_BUTTON_RELEASE) return false;
        updatePositions();
        Item target = hit(event.getX(), event.getY());
        if (target == null) return false;
        deliver(target, event, action, true);
        return true;
    }

    private void updatePositions() {
        for (Item item : items) {
            float x = 0, y = 0;
            item.inViewport = true;
            for (View current = item.view; current != host;) {
                x += current.getLeft(); y += current.getTop();
                if (!(current.getParent() instanceof ViewGroup parent)) { item.inViewport = false; break; }
                x -= parent.getScrollX(); y -= parent.getScrollY();
                if (parent instanceof android.widget.HorizontalScrollView)
                    item.inViewport &= x + item.view.getWidth() / 2f >= 0 && x + item.view.getWidth() / 2f < parent.getWidth();
                else if (parent instanceof android.widget.ScrollView)
                    item.inViewport &= y + item.view.getHeight() / 2f >= 0 && y + item.view.getHeight() / 2f < parent.getHeight();
                current = parent;
            }
            item.x = x; item.y = y;
        }
    }

    private boolean deliver(Item item, MotionEvent event, int action, boolean generic) {
        MotionEvent copy = MotionEvent.obtain(event);
        copy.setAction(action);
        float scale = item.view.getScaleX();
        copy.setLocation((event.getX() - item.left()) / scale, (event.getY() - item.top()) / scale);
        try { return generic ? item.view.dispatchGenericMotionEvent(copy) : item.view.dispatchTouchEvent(copy); }
        finally { copy.recycle(); }
    }

    private Item hit(float x, float y) {
        Item result = null;
        for (Item item : items) {
            if (!item.inViewport || !item.view.isShown() || !item.view.isEnabled()) continue;
            if (x >= item.left() && y >= item.top() && x < item.right() && y < item.bottom()
                    && (result == null || item.amount > result.amount)) result = item;
        }
        return result;
    }

    void input(Region region) {
        updatePositions();
        for (Item item : items) {
            if (item.amount == 0 || !item.inViewport || !item.view.isShown()) continue;
            bounds.set((int) Math.floor(item.left()), (int) Math.floor(item.top()),
                    (int) Math.ceil(item.right()), (int) Math.ceil(item.bottom()));
            region.op(bounds, Region.Op.UNION);
        }
    }

    private void animate() {
        stopAnimation();
        boolean changed = false;
        for (Item item : items) {
            item.from = item.amount;
            float extent = edge.vertical() ? item.view.getHeight() : item.view.getWidth();
            float center = (edge.vertical() ? item.y : item.x) + extent / 2;
            item.to = Float.isNaN(pointer) || !item.inViewport || !item.view.isEnabled() ? 0 : effect.influence(pointer - center, extent);
            changed |= Math.abs(item.from - item.to) > .001f;
        }
        if (!changed) return;
        int duration = motion.feedbackMs();
        if (duration == 0) { apply(1); return; }
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(duration);
        animator.setInterpolator(UiMotion.interpolator(motion.curve()));
        animator.addUpdateListener(value -> apply((float) value.getAnimatedValue()));
        animator.start();
    }

    private void apply(float fraction) {
        float density = host.getResources().getDisplayMetrics().density;
        for (Item item : items) {
            item.amount = item.from + (item.to - item.from) * fraction;
            float scale = 1 + (effect.scale() - 1) * item.amount;
            float lift = effect.liftDp() * density * item.amount;
            item.view.setScaleX(scale); item.view.setScaleY(scale);
            item.view.setTranslationX(edge == ShellPanel.Edge.LEFT ? lift : edge == ShellPanel.Edge.RIGHT ? -lift : 0);
            item.view.setTranslationY(edge == ShellPanel.Edge.TOP ? lift : edge == ShellPanel.Edge.BOTTOM ? -lift : 0);
            item.view.setTranslationZ(item.amount);
        }
        host.invalidate();
        inputChanged.run();
    }

    void reset() {
        stopAnimation();
        for (Item item : items) {
            item.view.setScaleX(1); item.view.setScaleY(1);
            item.view.setTranslationX(0); item.view.setTranslationY(0); item.view.setTranslationZ(0);
            item.view.setHovered(false); item.amount = 0;
        }
        hover = null; pressed = null; pointer = Float.NaN;
        if (!items.isEmpty()) { host.invalidate(); inputChanged.run(); }
    }

    private void stopAnimation() {
        if (animator != null) { animator.cancel(); animator.removeAllUpdateListeners(); animator = null; }
    }

    void clear() {
        reset(); items.clear();
        clips.forEach((view, clip) -> {
            view.setClipChildren(clip.children()); view.setClipToPadding(clip.padding()); view.setClipBounds(clip.bounds());
        });
        clips.clear(); root = null;
    }

    private record Clip(boolean children, boolean padding, Rect bounds) { }

    private static final class Item {
        final View view;
        float x, y;
        boolean inViewport = true;
        float amount, from, to;
        Item(View view, float x, float y) { this.view = view; this.x = x; this.y = y; }
        float left() { return x + view.getTranslationX() - view.getWidth() * (view.getScaleX() - 1) / 2; }
        float top() { return y + view.getTranslationY() - view.getHeight() * (view.getScaleY() - 1) / 2; }
        float right() { return left() + view.getWidth() * view.getScaleX(); }
        float bottom() { return top() + view.getHeight() * view.getScaleY(); }
    }
}
