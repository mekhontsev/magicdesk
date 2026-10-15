package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Passive chrome decoration driven by the existing typed task/focus observation. */
final class DesktopActiveWindowBorder {
    private final Context context;
    private final WindowManager windows;
    private final View[] edges = new View[4];
    private final Rect[] applied = {new Rect(), new Rect(), new Rect(), new Rect()};
    private final boolean[] added = new boolean[4];

    DesktopActiveWindowBorder(Context context, WindowManager windows) {
        this.context = context;
        this.windows = windows;
        for (int i = 0; i < edges.length; i++) {
            edges[i] = new View(context);
            edges[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    void update(IBinder token, Rect task, Rect workArea) {
        if (token == null || task == null || task.width() < 2 || task.height() < 2 || workArea == null) { clear(); return; }
        final int thickness = Math.max(1, Math.min(Math.round(2 * context.getResources().getDisplayMetrics().density),
                Math.min(task.width(), task.height()) / 2));
        // Four non-overlapping edge windows leave the application interior uncovered,
        // including for applications that reject obscured touches themselves.
        final Rect[] frames = {
            new Rect(task.left, task.top, task.right, task.top + thickness),
            new Rect(task.left, task.bottom - thickness, task.right, task.bottom),
            new Rect(task.left, task.top + thickness, task.left + thickness, task.bottom - thickness),
            new Rect(task.right - thickness, task.top + thickness, task.right, task.bottom - thickness)
        };
        boolean changed = false;
        for (int i = 0; i < frames.length; i++) {
            if (frames[i].isEmpty() || !frames[i].intersect(workArea)) frames[i].setEmpty();
            changed |= added[i] != !frames[i].isEmpty() || !applied[i].equals(frames[i]);
        }
        // Recreate the edges at the settled bounds to avoid four independent
        // window-manager move animations.
        if (changed) clear();
        for (int i = 0; i < edges.length; i++) {
            final Rect frame = frames[i];
            if (frame.isEmpty()) continue;
            edges[i].setBackgroundColor(UiAppearance.color(context, UiColor.ACCENT));
            if (added[i]) continue;
            final var params = new WindowManager.LayoutParams(frame.width(), frame.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.token = token;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = frame.left; params.y = frame.top;
            params.setFitInsetsTypes(0);
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            params.alpha = Math.min(0.7f, context.getSystemService(InputManager.class)
                    .getMaximumObscuringOpacityForTouch());
            params.setTitle("MagicDesk active window border " + i);
            params.windowAnimations = 0;
            windows.addView(edges[i], params);
            added[i] = true;
            applied[i].set(frame);
        }
    }

    private void remove(int edge) {
        if (added[edge]) windows.removeViewImmediate(edges[edge]);
        added[edge] = false;
        applied[edge].setEmpty();
    }

    void clear() { for (int i = 0; i < edges.length; i++) remove(i); }
}
