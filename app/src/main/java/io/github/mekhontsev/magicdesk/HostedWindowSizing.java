package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout;

/** Fits new managed dialogs as client limits settle, relinquishing sizing after a user resize. */
final class HostedWindowSizing {
    private boolean initialized, pending, manualSize;
    private int initialWidth, initialHeight;
    private Rect settled, submitted;

    void apply(Activity activity, ToolApplications.WindowPlacement placement,
            HostedWindowLayout layout, float scale, Runnable changed) {
        if (manualSize || pending || placement == null || !placement.target().desktop
                || layout.width() < 1 || layout.height() < 1) return;
        if (!activity.isInMultiWindowMode() || activity.isFinishing() || activity.isDestroyed()) return;
        var published = activity.getWindow().getDecorView().getRootWindowInsets();
        if (published == null) return;
        Insets caption = published.getInsets(WindowInsets.Type.captionBar());
        // The first layout can precede WMShell's caption publication. The next layout retries.
        if (Insets.NONE.equals(caption)) return;
        int display = placement.target().displayId;
        Rect work = DesktopRuntimeBridge.getDesktopWorkAreaBounds(display);
        if (work == null || work.isEmpty()) return;
        var metrics = activity.getWindowManager().getCurrentWindowMetrics();
        Insets decor = Insets.max(caption, metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars()));
        Rect bounds = metrics.getBounds();
        // A command receipt can precede View layout. Reconcile on its actual geometry event.
        if (submitted != null) {
            if (!bounds.equals(submitted)) return;
            settled = bounds;
            submitted = null;
        }
        if (settled != null && (bounds.width() != settled.width() || bounds.height() != settled.height())) {
            manualSize = true;
            return;
        }
        if (!initialized) {
            initialWidth = layout.width();
            initialHeight = layout.height();
            initialized = true;
        }
        int horizontal = decor.left + decor.right, vertical = decor.top + decor.bottom;
        int width = Math.min(work.width(), Math.round(layout.constraints().width(initialWidth) * scale) + horizontal);
        int height = Math.min(work.height(), Math.round(layout.constraints().height(initialHeight) * scale) + vertical);
        int left = bounds.centerX() - width / 2;
        int top = bounds.centerY() - height / 2;
        left = Math.max(work.left, Math.min(work.right - width, left));
        top = Math.max(work.top, Math.min(work.bottom - height, top));
        Rect target = new Rect(left, top, left + width, top + height);
        settled = bounds;
        if (target.equals(bounds)) return;
        submitted = target;
        pending = true;
        MagicDeskRuntime.setWindowBounds(display, activity.getTaskId(), target, result -> {
            pending = false;
            if (!result.success) {
                manualSize = true;
                CompatibilityDiagnostics.record("HOSTED-SIZE-001", "Could not apply hosted content size", result.message);
            }
            changed.run();
        });
    }
}
