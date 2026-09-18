package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/** Session-owned presentation reservations, independent of which viewer currently has focus. */
final class X11WindowPresentation {
    private final Context context;
    private final X11Sessions.Session session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Long> presented = new HashSet<>();
    private WeakReference<Activity> source = new WeakReference<>(null);
    private ToolApplications.SiblingPlacement placement;
    private long generation;
    private boolean closed;

    X11WindowPresentation(Context context, X11Sessions.Session session) {
        this.context = context; this.session = session;
    }

    void host(Activity activity) {
        if (closed) return;
        source = new WeakReference<>(activity);
        long version = ++generation;
        int display = activity.getDisplay() == null ? 0 : activity.getDisplay().getDisplayId();
        int task = activity.getTaskId();
        TaskCommandQueue.execute(() -> {
            try {
                var captured = ToolApplications.siblingPlacement(display, task);
                main.post(() -> {
                    if (closed || generation != version) return;
                    placement = captured;
                    session.presentationChanged();
                });
            } catch (java.io.IOException | RuntimeException error) {
                // A closing/moving host may lose its task; keep the last verified destination.
            }
        });
    }

    void claim(long window) { if (window != 0) presented.add(window); }
    void retain(Set<Long> windows) { presented.retainAll(windows); }
    void close() { closed = true; generation++; presented.clear(); source.clear(); }

    boolean present(long window) {
        if (closed || presented.contains(window)) return false;
        Activity activity = source.get();
        boolean live = activity != null && !activity.isFinishing() && !activity.isDestroyed();
        if (!live && (placement == null || !ShellAccess.isReady())) return false;
        presented.add(window);
        var intent = X11Activity.createIntent(context).putExtra(X11Activity.SESSION, session.id())
                .putExtra(X11Activity.WINDOW, window);
        BuiltInWindowLauncher.Callback done = error -> {
            if (error != null && !closed) session.presentationFailed(error);
        };
        try {
            if (live) ToolApplications.openSibling(activity, intent, done);
            else ToolApplications.open(context, intent, placement.target(), placement.uniqueId(), done);
        } catch (RuntimeException error) { done.onComplete(error); }
        return true;
    }
}
