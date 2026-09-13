package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ordered Binder work; UI callbacks never wait for a source application. */
final class DisplayViewerConnection {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "MagicDeskViewers"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private IDisplayViewer mLease;
    private android.os.IBinder.DeathRecipient mDeathRecipient;
    private final java.util.function.Consumer<Throwable> mFailure;
    private volatile long mGeneration;

    DisplayViewerConnection(java.util.function.Consumer<Throwable> failure) {
        mFailure = failure;
    }

    void attach(DesktopDisplayInfo source, DesktopDisplayInfo output, Surface surface, SurfaceControl parent,
            BuiltInWindowLauncher.Callback completion) {
        final long generation = ++mGeneration;
        // Retain our own native reference across SurfaceView destruction and queueing.
        final android.os.Parcel parcel = android.os.Parcel.obtain();
        final Surface copy;
        final SurfaceControl parentCopy;
        try {
            surface.writeToParcel(parcel, 0);
            parent.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            copy = Surface.CREATOR.createFromParcel(parcel);
            parentCopy = SurfaceControl.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
        WORKER.execute(() -> {
            try {
                release();
                if (generation != mGeneration) return;
                mLease = ShellAccess.openDisplayViewer(source, output, new Binder());
                mDeathRecipient = () -> WORKER.execute(() -> failed(generation,
                        new android.os.DeadObjectException()));
                mLease.asBinder().linkToDeath(mDeathRecipient, 0);
                mLease.attach(copy, parentCopy);
                complete(generation, completion, null);
            } catch (Exception error) {
                try { release(); } catch (Exception suppressed) { error.addSuppressed(suppressed); }
                complete(generation, completion, error);
            } finally { copy.release(); parentCopy.release(); }
        });
    }

    void motion(MotionEvent event) {
        final long generation = mGeneration;
        final MotionEvent copy = MotionEvent.obtain(event);
        WORKER.execute(() -> {
            try { if (generation == mGeneration && mLease != null) mLease.motion(copy); }
            catch (Exception error) { failed(generation, error); }
            finally { copy.recycle(); }
        });
    }

    void key(KeyEvent event) {
        final long generation = mGeneration;
        final KeyEvent copy = new KeyEvent(event);
        WORKER.execute(() -> {
            try { if (generation == mGeneration && mLease != null) mLease.key(copy); }
            catch (Exception error) { failed(generation, error); }
        });
    }

    void close() {
        close(error -> { });
    }

    void close(BuiltInWindowLauncher.Callback callback) {
        ++mGeneration;
        WORKER.execute(() -> {
            Throwable failure = null;
            try { release(); } catch (Exception error) { report(error); failure = error; }
            final Throwable result = failure;
            MAIN.post(() -> callback.onComplete(result));
        });
    }

    private void release() throws android.os.RemoteException {
        if (mLease != null) {
            final IDisplayViewer lease = mLease;
            mLease = null;
            if (mDeathRecipient != null) lease.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mDeathRecipient = null;
            lease.close();
        }
    }

    private void complete(long generation, BuiltInWindowLauncher.Callback callback, Throwable error) {
        MAIN.post(() -> { if (generation == mGeneration) callback.onComplete(error); });
    }

    private void failed(long generation, Exception error) {
        if (generation != mGeneration) return;
        try { release(); } catch (Exception suppressed) { error.addSuppressed(suppressed); }
        report(error);
        MAIN.post(() -> {
            if (generation != mGeneration) return;
            ++mGeneration;
            mFailure.accept(error);
        });
    }

    private static void report(Exception error) {
        CompatibilityDiagnostics.record("DISPLAY-VIEWER-001", "Display viewer operation failed",
                error.getMessage(), error);
    }
}
