package io.github.mekhontsev.magicdesk;

import android.os.Looper;
import io.github.mekhontsev.magicdesk.x11.X11Session;
import io.github.mekhontsev.magicdesk.x11.X11ShellSurface;
import java.util.List;
import java.util.function.Consumer;

/** A workspace contribution, independent from X server, guest WM and viewer lifetimes. */
final class X11ShellBinding implements AutoCloseable, X11Session.ShellListener {
    private final ShellLayoutScope mScope;
    private final X11ShellLayout mLayout;
    private final X11Session.ShellBinding mNative;
    private final Consumer<String> mEnded;
    private final Runnable mScopeChanged = this::reconcile;
    private ShellBounds mOutput;
    private boolean mClosed, mUpdating;
    private HostedShellWindows mWindows;

    X11ShellBinding(X11Session session, ShellLayoutScope scope, Consumer<String> ended) {
        checkThread();
        mScope = scope; mEnded = ended;
        mLayout = new X11ShellLayout(scope);
        try {
            mOutput = scope.snapshot().output();
            mNative = session.bindShell(mOutput.width(), mOutput.height(), this);
        } catch (RuntimeException error) { mLayout.close(); throw error; }
        scope.listen(mScopeChanged);
    }

    X11Session.Output openOutput(long id) { checkThread(); return mNative.openOutput(id); }
    void origin(String sessionId) { mLayout.origin(sessionId); }

    void host(HostedShellWindows.Host host, ShellPresentationScope presentation) {
        checkThread();
        if (mClosed || mWindows != null) throw new IllegalStateException("Shell already hosted or closed");
        var main = new android.os.Handler(Looper.getMainLooper());
        mWindows = new HostedShellWindows(host, presentation, main::post, error -> closed(error.toString()));
        updateWindows();
    }

    @Override public void changed() {
        checkThread();
        if (mClosed) return;
        if (mLayout.isClosed()) { closed("Shell layout scope was released"); return; }
        mUpdating = true;
        try { mLayout.update(mNative.surfaces()); }
        catch (RuntimeException error) { closed(error.toString()); }
        finally { mUpdating = false; }
        reconcile();
    }

    private void reconcile() {
        checkThread();
        if (mClosed || mUpdating) return;
        if (mLayout.isClosed()) { closed("Shell layout scope was released"); return; }
        var output = mScope.snapshot().output();
        if (!mOutput.equals(output)) {
            mOutput = output;
            mNative.resize(output.width(), output.height());
            changed();
            return;
        }
        updateWindows();
    }

    private void updateWindows() {
        if (mClosed || mWindows == null) return;
        try {
            var next = new java.util.ArrayList<HostedShellWindows.Surface>();
            for (var state : mNative.surfaces()) {
                var resolved = mLayout.surface(state.id());
                if (resolved == null) continue;
                var frame = frame(state);
                if (frame != null) frame = HostedShellPlacement.clip(resolved.content(), frame, mOutput, 160);
                var bounds = frame == null ? null : HostedShellPlacement.bounds(resolved.content(), frame, 160);
                next.add(new HostedShellWindows.Surface(state.id(), resolved.request().layer(),
                        resolved.request().keyboard(), bounds, frame));
            }
            mWindows.update(next);
        } catch (RuntimeException error) { closed(error.toString()); }
    }

    private static ShellBounds bounds(X11ShellSurface.Rect rect) {
        return new ShellBounds(rect.left(), rect.top(), rect.right(), rect.bottom());
    }
    private static HostedShellFrame frame(X11ShellSurface surface) {
        var paint = bounds(surface.paint());
        return !surface.mapped() || paint.isEmpty() ? null : new HostedShellFrame(paint, surface.inputComplete(),
                surface.input().stream().map(X11ShellBinding::bounds).toList());
    }

    @Override public void closed(String reason) {
        checkThread();
        if (mClosed) return;
        mClosed = true;
        mScope.unlisten(mScopeChanged);
        if (mWindows != null) mWindows.close();
        mNative.close();
        mLayout.close();
        mEnded.accept(reason);
    }
    @Override public void close() { closed(""); }
    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Shell binding requires the main thread");
    }
}
