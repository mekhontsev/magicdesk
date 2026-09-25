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
import io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout;

/** Session-owned presentation reservations, independent of which viewer currently has focus. */
final class HostedWindowPresentation {
    interface ContentHost {
        long hostedWindowId();
        HostedSurfaceView hostedSurface();
        boolean wholeDesktopViewer();
    }
    record Observation(int taskId, int displayId, long windowId, boolean focused,
            boolean attached, boolean wholeDesktop, HostedSurfaceView.Geometry geometry) { }
    interface Session {
        boolean ready();
        boolean containsWindow(long window);
        int hostTaskId(long window);
        Intent windowIntent(Context context, long window);
        void presentationChanged();
        default void observationChanged() { }
        void presentationFailed(Throwable error);
        default HostedWindowLayout layout(long window) { return HostedWindowLayout.NONE; }
        default float unitScale(Activity activity) { return 1; }
    }
    private final Context context;
    private final Session session;
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
        WeakReference<Activity> activity;
        android.view.View observed;
        android.view.View.OnLayoutChangeListener layout;
        android.view.View.OnAttachStateChangeListener attachment;
        void releaseObservation() {
            if (observed != null) {
                observed.removeOnLayoutChangeListener(layout);
                observed.removeOnAttachStateChangeListener(attachment);
                observed = null;
            }
        }
    }

    HostedWindowPresentation(Context context, Session session) {
        this.context = context; this.session = session;
    }

    void host(Activity activity) {
        if (closed) return;
        source = new WeakReference<>(activity);
        long version = ++generation;
        int display = activity.getDisplay() == null ? 0 : activity.getDisplay().getDisplayId();
        int task = activity.getTaskId();
        Host host = hosts.computeIfAbsent(task, key -> new Host());
        host.activity = new WeakReference<>(activity);
        if (activity instanceof ContentHost content && host.observed != content.hostedSurface()) {
            host.releaseObservation();
            host.observed = content.hostedSurface();
            if (host.observed != null) {
                host.layout = (v, l, t, r, b, ol, ot, or, ob) -> session.observationChanged();
                host.attachment = new android.view.View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(android.view.View view) { session.observationChanged(); }
                    public void onViewDetachedFromWindow(android.view.View view) { session.observationChanged(); }
                };
                host.observed.addOnLayoutChangeListener(host.layout);
                host.observed.addOnAttachStateChangeListener(host.attachment);
            }
        }
        session.observationChanged();
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
    java.util.List<Observation> observations() {
        var result = new java.util.ArrayList<Observation>();
        for (var entry : hosts.entrySet()) {
            Activity activity = entry.getValue().activity.get();
            if (activity == null || activity.isDestroyed() || activity.isFinishing()
                    || !(activity instanceof ContentHost content)) continue;
            var surface = content.hostedSurface();
            result.add(new Observation(entry.getKey(), activity.getDisplay() == null ? -1 : activity.getDisplay().getDisplayId(),
                    content.hostedWindowId(), activity.hasWindowFocus(), surface != null && surface.isAttachedToWindow(),
                    content.wholeDesktopViewer(), surface == null ? null : surface.geometry()));
        }
        return java.util.List.copyOf(result);
    }

    void detachViewer(int taskId) {
        var host = hosts.get(taskId);
        Activity activity = host == null ? null : host.activity.get();
        if (activity == null || activity.isDestroyed() || activity.isFinishing()
                || !(activity instanceof ContentHost content) || !content.wholeDesktopViewer())
            throw new IllegalArgumentException("Select a live whole-desktop viewer task");
        activity.finishAndRemoveTask();
    }
    boolean isClosed() { return closed; }
    void retain(Set<Long> windows) { presented.retainAll(windows); recovering.retainAll(windows); }
    void close() {
        closed = true; generation++; presented.clear(); recovering.clear();
        hosts.values().forEach(Host::releaseObservation); hosts.clear(); source.clear();
        session.observationChanged();
    }

    void hostRemoved(Activity activity, long window, boolean clientCloseRequested) {
        Host host = hosts.remove(activity.getTaskId());
        if (host != null) host.releaseObservation();
        session.observationChanged();
        if (closed || !clientCloseRequested || window == 0 || !recovering.add(window)) return;
        final DesktopLaunchPresentation replacement;
        final Intent replacementIntent = intent(window);
        try {
            if (host == null || host.placement == null) throw new IllegalStateException("Host placement is unavailable");
            replacement = ToolApplications.replacementPresentation(activity, host.placement);
            SystemBarInsets.preserveCaption(activity, replacementIntent);
        } catch (RuntimeException error) {
            recovering.remove(window);
            session.presentationFailed(error);
            return;
        }
        // Let teardown finish, then use the current catalog. No guessed client-response delay.
        main.post(() -> {
            if (!recovering.remove(window) || closed || !session.ready()
                    || !session.containsWindow(window)
                    || session.hostTaskId(window) >= 0) return;
            if (!ShellAccess.isReady()) {
                session.presentationFailed(new IllegalStateException("Cannot restore the closed application host automatically"));
                return;
            }
            presented.add(window);
            var captured = host.placement;
            try {
                ToolApplications.open(context, replacementIntent, captured.target(), captured.uniqueId(),
                        replacement, error -> presentationCompleted(window, error));
            } catch (RuntimeException error) { presentationCompleted(window, error); }
        });
    }

    private Intent intent(long window) {
        return session.windowIntent(context, window);
    }

    boolean present(long window) {
        if (closed || presented.contains(window)) return false;
        Activity activity = source.get();
        var layout = session.layout(window);
        Host parent = layout.parent() == 0 ? null : hosts.get(session.hostTaskId(layout.parent()));
        Activity parentActivity = parent == null || parent.activity == null ? null : parent.activity.get();
        if (parentActivity != null && !parentActivity.isDestroyed() && !parentActivity.isFinishing()) activity = parentActivity;
        boolean live = activity != null && !activity.isFinishing() && !activity.isDestroyed();
        if (!live && (placement == null || !ShellAccess.isReady())) return false;
        presented.add(window);
        var intent = intent(window);
        BuiltInWindowLauncher.Callback done = error -> presentationCompleted(window, error);
        try {
            if (live) ToolApplications.openSibling(activity, intent,
                    ToolApplications.childPresentation(activity, layout, session.unitScale(activity)), done);
            else ToolApplications.open(context, intent, placement.target(), placement.uniqueId(), done);
        } catch (RuntimeException error) { done.onComplete(error); }
        return true;
    }

    private void presentationCompleted(long window, Throwable error) {
        // A client can finish while its Android replacement is still being launched.
        if (error != null && !closed && session.ready() && session.containsWindow(window)) {
            session.presentationFailed(error);
        }
    }
}
