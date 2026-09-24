package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Rect;
import android.view.MotionEvent;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture;
import io.github.mekhontsev.magicdesk.hosted.HostedMaximization;
import java.util.function.BiConsumer;

/** Client requests enter the same Desktop gateway as user commands. No task-area ownership here. */
final class HostedWindowCommands implements AutoCloseable {
    private final Activity activity;
    private final HostedSurfaceView surface;
    private final BiConsumer<Long, HostedMaximization> confirmation;
    private long serial = -1;
    private boolean closed, submitting, maximizing;
    private HostedMaximization confirmed;
    private HostedWindowGesture gesture;
    private HostedWindowConstraints constraints = HostedWindowConstraints.NONE;
    private Rect initial, pending;
    private float startX, startY, scale = 1;
    private int horizontalDecor, verticalDecor;

    HostedWindowCommands(Activity activity, HostedSurfaceView surface, BiConsumer<Long, HostedMaximization> confirmation) {
        this.activity = activity; this.surface = surface; this.confirmation = confirmation;
        surface.windowMotion(this::motion);
    }

    void update(long revision, HostedMaximization requested, HostedWindowConstraints constraints, float scale) {
        this.constraints = constraints; this.scale = scale;
        if (closed) return;
        if (serial == revision) { observe(); return; }
        serial = revision;
        if (revision == 0) { observe(); return; }
        if (!managedAvailable()) { confirm(HostedMaximization.NONE); return; }
        long command = serial;
        maximizing = true;
        MagicDeskRuntime.setMaximized(display(), activity.getTaskId(), requested, result -> activity.runOnUiThread(() -> {
            if (closed || serial != command) return;
            // Observe the framework result, not acceptance or an Activity layout still in flight.
            TaskRepository.load(display(), snapshot -> activity.runOnUiThread(() -> {
                if (closed || serial != command) return;
                maximizing = false;
                var task = snapshot.tasks.stream().filter(item -> item.taskId == activity.getTaskId()).findFirst().orElse(null);
                Rect work = DesktopRuntimeBridge.getDesktopWorkAreaBounds(display());
                if (snapshot.available && task != null)
                    confirm(task.isFreeform() ? WindowMaximization.observe(task.bounds, work) : HostedMaximization.NONE);
            }));
        }));
    }

    void observe() {
        if (closed || maximizing) return;
        Rect work = DesktopRuntimeBridge.getDesktopWorkAreaBounds(display());
        var actual = managedAvailable()
                ? WindowMaximization.observe(activity.getWindowManager().getCurrentWindowMetrics().getBounds(), work) : HostedMaximization.NONE;
        if (confirmed == null || actual != confirmed) confirm(actual);
    }

    private void confirm(HostedMaximization actual) { confirmed = actual; confirmation.accept(serial, actual); }
    private int display() { return activity.getDisplay() == null ? 0 : activity.getDisplay().getDisplayId(); }
    private boolean managedAvailable() {
        return activity.isInMultiWindowMode() && DesktopRuntimeBridge.hasWorkspace(display());
    }

    void begin(HostedWindowGesture kind) {
        if (kind == HostedWindowGesture.CANCEL) {
            if (gesture != null) { endGesture(); pending = null; }
            return;
        }
        var point = surface.pressedPointer();
        if (closed || maximizing || gesture != null || point == null || !managedAvailable()) return;
        initial = new Rect(activity.getWindowManager().getCurrentWindowMetrics().getBounds());
        startX = point.x; startY = point.y;
        horizontalDecor = Math.max(0, initial.width() - surface.getWidth());
        verticalDecor = Math.max(0, initial.height() - surface.getHeight());
        gesture = kind;
        surface.beginWindowGesture();
    }

    private boolean motion(MotionEvent event) {
        if (gesture == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || !activity.hasWindowFocus() || !managedAvailable()) {
            endGesture(); pending = null; return true;
        }
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            int dx = Math.round(event.getRawX() - startX), dy = Math.round(event.getRawY() - startY);
            pending = new Rect(initial);
            if (gesture == HostedWindowGesture.MOVE) pending.offset(dx, dy);
            else {
                int width = initial.width() + (gesture.right ? dx : gesture.left ? -dx : 0);
                int height = initial.height() + (gesture.bottom ? dy : gesture.top ? -dy : 0);
                width = Math.round(constraints.width(Math.round((width - horizontalDecor) / scale)) * scale) + horizontalDecor;
                height = Math.round(constraints.height(Math.round((height - verticalDecor) / scale)) * scale) + verticalDecor;
                if (gesture.left) pending.left = pending.right - width; else pending.right = pending.left + width;
                if (gesture.top) pending.top = pending.bottom - height; else pending.bottom = pending.top + height;
            }
            submitBounds();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_BUTTON_RELEASE) endGesture();
        return true;
    }

    private void submitBounds() {
        if (closed || submitting || pending == null) return;
        Rect target = pending; pending = null; submitting = true;
        MagicDeskRuntime.setWindowBounds(display(), activity.getTaskId(), target, result -> activity.runOnUiThread(() -> {
            submitting = false;
            if (!result.success) { endGesture(); pending = null; }
            if (!closed) submitBounds();
        }));
    }

    private void endGesture() {
        if (gesture == null) return;
        gesture = null;
        surface.cancelPointer();
    }
    void focusChanged() { if (!activity.hasWindowFocus()) { endGesture(); pending = null; } }
    @Override public void close() { closed = true; endGesture(); pending = null; surface.windowMotion(null); }
}
