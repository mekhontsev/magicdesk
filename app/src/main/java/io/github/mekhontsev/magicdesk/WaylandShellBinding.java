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
    List<WaylandShellSurface> surfaces() { return mClosed ? List.of() : mNative.surfaces(); }
    ShellLayout.Surface surface(final long id) { return mLayout.surface(id); }
    WaylandViewGeometry geometry(final long id) { return mNative.geometry(id); }

    @Override public void geometryChanged(final long id) {
        checkThread();
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
        mListener.changed();
    }

    @Override public void closed(final String reason) {
        checkThread();
        if (mClosed) return;
        mClosed = true;
        mScope.unlisten(mScopeChanged);
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
