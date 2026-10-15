package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Chrome input surfaces over exposed portions of offscreen columns. */
final class DesktopPaperFocusShields {
    record Target(int taskId, Rect bounds) {}
    private final Context context;
    private final WindowManager windows;
    private final List<View> views = new ArrayList<>();
    private List<Target> targets = List.of();
    private IntConsumer activate;

    DesktopPaperFocusShields(Context context, WindowManager windows) {
        this.context = context;
        this.windows = windows;
    }

    void update(IBinder token, List<Target> next, IntConsumer activate) {
        this.activate = activate;
        if (token == null) { clear(); return; }
        if (targets.equals(next)) return;
        clearViews();
        targets = List.copyOf(next);
        for (Target target : targets) {
            View view = new View(context);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Click fires on UP: the complete initial gesture stays on this
            // surface, even if focusing subsequently removes the shield.
            view.setOnClickListener(v -> {
                if (this.activate != null) this.activate.accept(target.taskId());
            });
            Rect frame = target.bounds();
            var params = new WindowManager.LayoutParams(frame.width(), frame.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.token = token;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = frame.left; params.y = frame.top;
            params.setFitInsetsTypes(0);
            params.setTitle("MagicDesk column focus " + target.taskId());
            windows.addView(view, params);
            views.add(view);
        }
    }

    private void clearViews() {
        for (View view : views) windows.removeViewImmediate(view);
        views.clear();
        targets = List.of();
    }

    void clear() { activate = null; clearViews(); }
}
