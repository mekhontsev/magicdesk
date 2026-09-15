package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import java.util.LinkedHashMap;
import java.util.Map;

/** A presentation/input lease. Revocation never removes the source display. */
final class ShellDisplayViewer extends IDisplayViewer.Stub {
    private final DisplayPresentationSurface mPresentation;
    private final int mSourceDisplayId;
    private final IBinder mOwner;
    private final IBinder.DeathRecipient mDeath = () -> {
        try { close(); }
        catch (RuntimeException error) { android.util.Log.w("MagicDeskDisplays", "Viewer owner cleanup failed", error); }
    };
    private final Map<Integer, KeyEvent> mKeys = new LinkedHashMap<>();
    private Surface mSurface;
    private MotionEvent mTouch;
    private boolean mClosed;
    final int outputDisplayId;
    final boolean direct;

    int sourceDisplayId() { return mSourceDisplayId; }

    ShellDisplayViewer(DisplayPresentationSurface presentation, int sourceDisplayId, int outputDisplayId,
            boolean direct, IBinder owner) throws RemoteException {
        mPresentation = presentation;
        mSourceDisplayId = sourceDisplayId;
        this.outputDisplayId = outputDisplayId;
        this.direct = direct;
        mOwner = owner;
        owner.linkToDeath(mDeath, 0);
    }

    synchronized boolean isClosed() { return mClosed; }

    @Override public synchronized void attach(Surface surface, SurfaceControl parent) {
        if (mClosed) {
            if (surface != null) surface.release();
            if (parent != null) parent.release();
            throw new IllegalStateException("viewer lease was closed");
        }
        try { mPresentation.attach(surface, parent); }
        catch (RuntimeException error) {
            if (surface != null) surface.release();
            throw error;
        } finally { if (parent != null) parent.release(); }
        if (mSurface != null) mSurface.release();
        mSurface = surface;
    }

    @Override public synchronized void motion(MotionEvent event) {
        if (event == null) throw new IllegalArgumentException("missing motion event");
        try {
            requireAttached();
            inject(event);
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || mTouch != null) {
                if (mTouch != null) mTouch.recycle();
                mTouch = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        ? null : MotionEvent.obtain(event);
            }
        } finally { event.recycle(); }
    }

    @Override public synchronized void key(KeyEvent event) {
        requireAttached();
        if (event == null) throw new IllegalArgumentException("missing key event");
        inject(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) mKeys.put(event.getKeyCode(), event);
        else if (event.getAction() == KeyEvent.ACTION_UP) mKeys.remove(event.getKeyCode());
    }

    private void requireAttached() {
        if (mClosed || mSurface == null) throw new IllegalStateException("viewer is not attached");
    }

    private void inject(InputEvent event) {
        try {
            // Acceptance only: waiting for app delivery can deadlock a viewer's
            // input callback with the task to which it forwards the event.
            FrameworkRuntime.current().inputInjection().inject(
                    mSourceDisplayId, event, 0);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("could not inject viewer input", error);
        }
    }

    @Override public synchronized void close() {
        if (mClosed) return;
        mClosed = true;
        RuntimeException failure = null;
        try {
            if (mTouch != null) {
                mTouch.setAction(MotionEvent.ACTION_CANCEL);
                try { inject(mTouch); }
                catch (RuntimeException error) { failure = error; }
                finally { mTouch.recycle(); mTouch = null; }
            }
            for (KeyEvent key : mKeys.values()) {
                try {
                    inject(KeyEvent.changeTimeRepeat(KeyEvent.changeAction(key, KeyEvent.ACTION_UP),
                            SystemClock.uptimeMillis(), 0, key.getFlags() | KeyEvent.FLAG_CANCELED));
                } catch (RuntimeException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
        } finally {
            mKeys.clear();
            try { mPresentation.close(); }
            finally {
                if (mSurface != null) { mSurface.release(); mSurface = null; }
                mOwner.unlinkToDeath(mDeath, 0);
            }
        }
        if (failure != null) throw failure;
    }
}
