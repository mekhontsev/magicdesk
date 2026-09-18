package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Session-owned presentation reservations, independent of which viewer currently has focus. */
final class X11WindowPresentation {
    private final Context context;
    private final X11Sessions.Session session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Long> presented = new HashSet<>();
    private WeakReference<Activity> source = new WeakReference<>(null);
    private ToolApplications.WindowPlacement placement;
    private final Map<Integer, Host> hosts = new HashMap<>();
    private final Set<Long> recovering = new HashSet<>();
    private long generation;
    private boolean closed;

    private static final class Host {
        long generation;
        ToolApplications.WindowPlacement placement;
    }

    X11WindowPresentation(Context context, X11Sessions.Session session) {
        this.context = context; this.session = session;
    }

    void host(Activity activity) {
        if (closed) return;
        source = new WeakReference<>(activity);
        long version = ++generation;
        int display = activity.getDisplay() == null ? 0 : activity.getDisplay().getDisplayId();
        int task = activity.getTaskId();
        Host host = hosts.computeIfAbsent(task, key -> new Host());
        host.generation = version;
        TaskCommandQueue.execute(() -> {
            try {
                var captured = ToolApplications.windowPlacement(display, task);
                main.post(() -> {
                    if (closed || hosts.get(task) != host || host.generation != version) return;
                    host.placement = captured;
                    if (generation == version) placement = captured;
                    session.presentationChanged();
                });
            } catch (java.io.IOException | RuntimeException error) {
                // A closing/moving host may lose its task; keep the last verified destination.
            }
        });
    }

    void claim(long window) { if (window != 0) presented.add(window); }
    boolean isClosed() { return closed; }
    void retain(Set<Long> windows) { presented.retainAll(windows); recovering.retainAll(windows); }
    void close() { closed = true; generation++; presented.clear(); recovering.clear(); hosts.clear(); source.clear(); }

    void hostRemoved(Activity activity, long window, boolean clientCloseRequested) {
        Host host = hosts.remove(activity.getTaskId());
        if (closed || !clientCloseRequested || window == 0 || !recovering.add(window)) return;
        final DesktopLaunchPresentation replacement;
        final Intent replacementIntent = intent(window);
        try {
            if (host == null || host.placement == null) throw new IllegalStateException("X11 host placement is unavailable");
            replacement = ToolApplications.replacementPresentation(activity, host.placement);
        } catch (RuntimeException error) {
            recovering.remove(window);
            session.presentationFailed(error);
            return;
        }
        // Let teardown finish, then use the current catalog. No guessed client-response delay.
        main.post(() -> {
            if (!recovering.remove(window) || closed || session.state() != X11Sessions.State.READY
                    || session.windows().stream().noneMatch(item -> item.id() == window)
                    || session.hostTaskId(window) >= 0) return;
            if (!ShellAccess.isReady()) {
                session.presentationFailed(new IllegalStateException("Cannot restore the closed X11 host automatically"));
                return;
            }
            presented.add(window);
            var captured = host.placement;
            try {
                ToolApplications.open(context, replacementIntent, captured.target(), captured.uniqueId(),
                        replacement, error -> { if (error != null && !closed) session.presentationFailed(error); });
            } catch (RuntimeException error) { if (!closed) session.presentationFailed(error); }
        });
    }

    private Intent intent(long window) {
        return X11Activity.windowIntent(context, session, window);
    }

    boolean present(long window) {
        if (closed || presented.contains(window)) return false;
        Activity activity = source.get();
        boolean live = activity != null && !activity.isFinishing() && !activity.isDestroyed();
        if (!live && (placement == null || !ShellAccess.isReady())) return false;
        presented.add(window);
        var intent = intent(window);
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
