package io.github.mekhontsev.magicdesk;

import android.os.Looper;
import io.github.mekhontsev.magicdesk.wayland.WaylandSession;
import io.github.mekhontsev.magicdesk.wayland.WaylandShellSurface;
import io.github.mekhontsev.magicdesk.wayland.WaylandViewGeometry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Main-thread protocol/layout bridge. The caller supplies the scope; this never starts Desktop. */
final class WaylandShellBinding implements AutoCloseable, WaylandSession.ShellListener {
    interface Listener {
        void changed();
        void closed(String reason);
        default void geometryChanged(long surface) { }
    }

    private final ShellLayoutScope mScope;
    private final WaylandShellLayout mLayout;
    private final WaylandSession.ShellBinding mNative;
    private final Listener mListener;
    private final Runnable mScopeChanged = this::reconcile;
    private Map<Long, WaylandShellLayout.Configuration> mConfigured = Map.of();
    private ShellBounds mOutput;
    private int mDensity;
    private boolean mClosed, mUpdating;
    private HostedShellWindows mWindows;
    private ShellTaskCatalog mTasks;
    private final Runnable mTasksChanged = this::publishTasks;
    private Map<Long, WaylandSession.Toplevel> mPublishedTasks = Map.of();
    private boolean mTasksReady;

    WaylandShellBinding(final WaylandSession session, final ShellLayoutScope scope,
            final int density, final Listener listener) {
        checkThread();
        mScope = java.util.Objects.requireNonNull(scope);
        mListener = java.util.Objects.requireNonNull(listener);
        mLayout = new WaylandShellLayout(scope);
        try {
            mLayout.update(List.of(), density);
            mDensity = density;
            mOutput = mLayout.output();
            mNative = session.bindShell(mOutput.width(), mOutput.height(), this);
        } catch (RuntimeException error) { mLayout.close(); throw error; }
        mScope.listen(mScopeChanged);
    }

    java.util.concurrent.CompletableFuture<Void> ready() { return mNative.ready(); }

    void tasks(ShellTaskCatalog catalog) {
        checkThread();
        if (mClosed || mTasks != null) throw new IllegalStateException("Shell task catalog already bound or closed");
        mTasks = java.util.Objects.requireNonNull(catalog);
        mTasks.listen(mTasksChanged);
        var main = new android.os.Handler(Looper.getMainLooper());
        mNative.ready().whenComplete((ignored, error) -> main.post(() -> {
            if (mClosed) return;
            if (error != null) closed(error.toString());
            else { mTasksReady = true; publishTasks(); }
        }));
    }

    private void publishTasks() {
        if (mClosed || mTasks == null || !mTasksReady) return;
        var next = new LinkedHashMap<Long, WaylandSession.Toplevel>();
        for (var window : mTasks.snapshot()) {
            var task = window.task();
            var value = new WaylandSession.Toplevel(window.id(), task.title(), task.appId(), task.active(), task.maximized(), task.fullscreen(), task.minimized());
            next.put(window.id(), value);
            if (!value.equals(mPublishedTasks.get(window.id()))) mNative.publishToplevel(value, false);
        }
        for (var previous : mPublishedTasks.values()) if (!next.containsKey(previous.id())) mNative.publishToplevel(previous, true);
        mPublishedTasks = Map.copyOf(next);
    }

    @Override public void toplevelAction(long id, WaylandSession.ToplevelAction action) {
        checkThread();
        if (mClosed || mTasks == null) return;
        mTasks.request(id, switch (action) {
            case ACTIVATE -> ShellTaskCatalog.Action.ACTIVATE;
            case MAXIMIZE -> ShellTaskCatalog.Action.MAXIMIZE;
            case FULLSCREEN -> ShellTaskCatalog.Action.FULLSCREEN;
            case UNMAXIMIZE -> ShellTaskCatalog.Action.UNMAXIMIZE;
            case UNFULLSCREEN -> ShellTaskCatalog.Action.UNFULLSCREEN;
            case CLOSE -> ShellTaskCatalog.Action.CLOSE;
            case MINIMIZE -> ShellTaskCatalog.Action.MINIMIZE;
            case UNMINIMIZE -> ShellTaskCatalog.Action.UNMINIMIZE;
        });
    }
    List<WaylandShellSurface> surfaces() { return mClosed ? List.of() : mNative.surfaces(); }
    ShellLayout.Surface surface(final long id) { return mLayout.surface(id); }
    WaylandViewGeometry geometry(final long id) { return mNative.geometry(id); }

    HostedShellWindows host(final HostedShellWindows.Host host, final ShellPresentationScope presentation) {
        checkThread();
        if (mClosed || mWindows != null) throw new IllegalStateException("Shell binding already hosted or closed");
        final var main = new android.os.Handler(Looper.getMainLooper());
        mWindows = new HostedShellWindows(host, presentation, main::post, error -> closed(error.toString()));
        updateWindows();
        return mWindows;
    }

    private void updateWindows() {
        if (mWindows == null || mClosed) return;
        try {
            final var next = new java.util.ArrayList<HostedShellWindows.Surface>();
            for (var state : surfaces()) {
                var resolved = surface(state.id());
                if (resolved == null) continue;
                var frame = state.mapped() ? frame(geometry(state.id())) : null;
                if (frame != null) frame = HostedShellPlacement.clip(resolved.content(), frame, mScope.snapshot().output(), mDensity);
                var bounds = frame == null ? null : HostedShellPlacement.bounds(resolved.content(), frame, mDensity);
                next.add(new HostedShellWindows.Surface(state.id(), resolved.request().layer(),
                        resolved.request().keyboard(), bounds, frame));
            }
            mWindows.update(next);
        } catch (RuntimeException error) { closed(error.toString()); }
    }

    static HostedShellFrame frame(final WaylandViewGeometry geometry) {
        if (geometry == null || !geometry.mapped()) return null;
        final var paint = geometry.paint();
        if (paint.right() == paint.left() || paint.bottom() == paint.top()) return null;
        return new HostedShellFrame(new ShellBounds(paint.left(), paint.top(), paint.right(), paint.bottom()),
                geometry.inputComplete(), geometry.input().stream()
                .map(rect -> new ShellBounds(rect.left(), rect.top(), rect.right(), rect.bottom())).toList());
    }

    @Override public void geometryChanged(final long id) {
        checkThread();
        if (mClosed) return;
        updateWindows();
        if (!mClosed) mListener.geometryChanged(id);
    }

    WaylandSession.Output openOutput(final long id, final int width, final int height) {
        checkThread();
        if (mClosed) throw new IllegalStateException("Shell workspace binding is closed");
        return mNative.openOutput(id, width, height);
    }

    void density(final int density) {
        checkThread();
        if (mClosed) throw new IllegalStateException("Shell workspace binding is closed");
        if (density <= 0) throw new IllegalArgumentException("Shell density must be positive");
        mDensity = density;
        changed();
    }

    @Override public void changed() {
        checkThread();
        if (mClosed) return;
        if (mLayout.isClosed()) { closed("Shell layout scope was released"); return; }
        mUpdating = true;
        try { mLayout.update(mNative.surfaces(), mDensity); }
        finally { mUpdating = false; }
        reconcile();
    }

    private void reconcile() {
        checkThread();
        if (mClosed || mUpdating) return;
        if (mLayout.isClosed()) { closed("Shell layout scope was released"); return; }
        final ShellBounds output = mLayout.output();
        if (!output.equals(mOutput)) {
            mOutput = output;
            mNative.resize(output.width(), output.height());
        }
        final Map<Long, WaylandShellLayout.Configuration> next = new LinkedHashMap<>();
        for (var configuration : mLayout.configurations()) {
            next.put(configuration.id(), configuration);
            if (configuration.equals(mConfigured.get(configuration.id()))) continue;
            final var bounds = configuration.bounds();
            mNative.configure(configuration.id(), configuration.revision(), bounds.left(), bounds.top(),
                    bounds.width(), bounds.height());
        }
        mConfigured = next;
        updateWindows();
        if (!mClosed) mListener.changed();
    }

    @Override public void closed(final String reason) {
        checkThread();
        if (mClosed) return;
        mClosed = true;
        if (mTasks != null) mTasks.unlisten(mTasksChanged);
        mPublishedTasks = Map.of();
        mScope.unlisten(mScopeChanged);
        if (mWindows != null) mWindows.close();
        mNative.close();
        mLayout.close();
        mConfigured = Map.of();
        mListener.closed(reason);
    }

    @Override public void close() { closed(""); }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Shell workspace binding requires the main thread");
    }
}
