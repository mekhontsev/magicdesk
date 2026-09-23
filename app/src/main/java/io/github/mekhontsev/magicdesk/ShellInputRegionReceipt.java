package io.github.mekhontsev.magicdesk;

import android.graphics.Region;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import java.io.Closeable;

/** Cancellable one-shot input admission. No worker thread, polling or task/session ownership. */
final class ShellInputRegionReceipt extends IInputRegionReceipt.Stub {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IInputRegionCallback callback;
    private final IBinder.DeathRecipient death = this::cancel;
    private final Runnable timeout = () -> finish("Window input acknowledgement timed out", true);
    private Closeable observation;
    private boolean finished, linked;

    ShellInputRegionReceipt(IBinder window, int displayId, Region region, IInputRegionCallback callback) {
        this.callback = java.util.Objects.requireNonNull(callback);
        if (window == null || displayId < 0 || region == null)
            throw new IllegalArgumentException("A window, display and input region are required");
        var expected = new Region(region);
        handler.post(() -> start(window, displayId, expected));
    }

    private void start(IBinder window, int displayId, Region region) {
        if (finished) return;
        try {
            callback.asBinder().linkToDeath(death, 0);
            linked = true;
            // EVENT_WAIT: exact SF region, then InputDispatcher receipt. Deadline rejects, never admits.
            EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.INPUT_WINDOW_COMMIT);
            handler.postDelayed(timeout, 2_000);
            observation = FrameworkInputWindowObservationSource.observeTouchableRegion(
                    window, displayId, region, error -> handler.post(() -> observed(error)));
        } catch (ReflectiveOperationException | RuntimeException | RemoteException | LinkageError error) {
            finish(error.toString(), true);
        }
    }

    private void observed(Throwable error) {
        if (finished) return;
        closeObservation();
        if (error != null) { finish(error.toString(), true); return; }
        try {
            FrameworkSurfaceInputApi.reportInputWindows(() -> handler.post(() -> finish(null, true)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            finish(failure.toString(), true);
        }
    }

    @Override public void cancel() { handler.post(() -> finish(null, false)); }

    private void finish(String error, boolean notify) {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(timeout);
        closeObservation();
        if (linked) callback.asBinder().unlinkToDeath(death, 0);
        linked = false;
        if (notify) {
            try { callback.completed(error); }
            catch (RemoteException ignored) { /* The requester no longer owns this receipt. */ }
        }
    }

    private void closeObservation() {
        var previous = observation;
        observation = null;
        if (previous != null) {
            try { previous.close(); }
            catch (java.io.IOException | RuntimeException error) {
                Log.w("MagicDeskInputRegion", "Could not release input-region observation", error);
            }
        }
    }
}
