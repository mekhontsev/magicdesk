package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.view.Display;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes route ownership; physical input never passes through this process. */
final class DisplayInputSession {
    private final Handler mHandler;
    private final Runnable mChanged;
    private final DesktopMouseBridge mMouse;
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "MagicDeskInputSession");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean mRefreshQueued = new AtomicBoolean();
    private volatile int mRequestedDisplay = Display.INVALID_DISPLAY;
    private volatile int mReadyDisplay = Display.INVALID_DISPLAY;
    private volatile long mGeneration;
    private ShellInputRoutingHandle mRouting;
    private boolean mDestroyed;
    private boolean mDesktopShortcuts;
    private volatile boolean mTransitioning;
    private volatile String mError = "";

    DisplayInputSession(final Context context, final Handler handler, final Runnable changed) {
        mHandler = handler;
        mChanged = changed;
        mMouse = new DesktopMouseBridge(context, () -> mHandler.post(changed));
    }

    void reconcile(final int displayId, final boolean desktopShortcuts) {
        if (mDestroyed || mRequestedDisplay == displayId
                && mDesktopShortcuts == desktopShortcuts) {
            return;
        }
        mRequestedDisplay = displayId;
        mDesktopShortcuts = desktopShortcuts;
        final long generation = ++mGeneration;
        mTransitioning = true;
        mError = "";
        mReadyDisplay = Display.INVALID_DISPLAY;
        DesktopShortcutService.setTargetDisplay(Display.INVALID_DISPLAY);
        mWorker.execute(() -> {
            try {
                if (!release() || mGeneration != generation || displayId < 0) {
                    return;
                }
                InputSessionDiagnostics.noteAttempt(displayId);
                mRouting = ShellAccess.openInputRouting(displayId, desktopShortcuts);
                if (mGeneration != generation) {
                    release();
                    return;
                }
                mReadyDisplay = displayId;
                InputSessionDiagnostics.noteReady();
                if (displayId > Display.DEFAULT_DISPLAY) {
                    mMouse.start();
                }
                mHandler.post(() -> {
                    if (mGeneration == generation && mReadyDisplay == displayId) {
                        DesktopShortcutService.setTargetDisplay(desktopShortcuts ? displayId : -1);
                        mChanged.run();
                    }
                });
            } catch (IOException error) {
                report("INPUT-ROUTING-001", "Could not establish display input", error);
                release();
            } finally {
                if (mGeneration == generation) {
                    mTransitioning = false;
                    mHandler.post(mChanged);
                }
            }
        });
    }

    void refreshDevices() {
        if (mDestroyed || mRequestedDisplay < 0
                || !mRefreshQueued.compareAndSet(false, true)) {
            return;
        }
        mWorker.execute(() -> {
            mRefreshQueued.set(false);
            if (mRouting == null || mRequestedDisplay != mRouting.displayId()) {
                return;
            }
            final long generation = mGeneration;
            try {
                mRouting.refresh();
                mError = "";
                if (mGeneration == generation) mReadyDisplay = mRouting.displayId();
            } catch (IOException error) {
                if (mGeneration == generation) mReadyDisplay = Display.INVALID_DISPLAY;
                InputSessionDiagnostics.noteSourceRefreshFailure(error);
                report("INPUT-ROUTING-002", "Could not refresh display input routes", error);
            }
            mHandler.post(() -> {
                if (mGeneration == generation) {
                    DesktopShortcutService.setTargetDisplay(mDesktopShortcuts ? mReadyDisplay : -1);
                    mChanged.run();
                }
            });
        });
    }

    void stop(final Runnable completion) {
        mError = "";
        mRequestedDisplay = Display.INVALID_DISPLAY;
        final long generation = ++mGeneration;
        mTransitioning = true;
        mReadyDisplay = Display.INVALID_DISPLAY;
        DesktopShortcutService.setTargetDisplay(Display.INVALID_DISPLAY);
        // Queue behind an in-flight acquisition. Never block the Activity thread,
        // and never remove a display before this ownership boundary has completed.
        mWorker.execute(() -> {
            try {
                release();
            } finally {
                if (mGeneration == generation) { mTransitioning = false; }
                mHandler.post(completion);
                mHandler.post(mChanged);
            }
        });
    }

    void destroy() {
        if (!mDestroyed) {
            stop(() -> {});
            mDestroyed = true;
            mWorker.shutdown();
        }
    }

    boolean isRoutingReady(final int displayId) {
        return displayId >= 0 && mRequestedDisplay == displayId && mReadyDisplay == displayId;
    }

    boolean isPointerReady(final int displayId) {
        return displayId > Display.DEFAULT_DISPLAY && isRoutingReady(displayId) && mMouse.isReady();
    }

    int readyDisplay() { return mReadyDisplay; }
    boolean transitioning() { return mTransitioning; }
    String error() { return mError; }

    boolean movePointer(final float x, final float y) { return mMouse.movePointer(x, y); }
    boolean clickPointer(final int button) { return mMouse.clickPointer(button); }
    boolean setPrimaryButtonPressed(final boolean pressed) { return mMouse.setPrimaryButtonPressed(pressed); }
    boolean scrollPointer(final float amount) { return mMouse.scrollPointer(amount); }

    DesktopInputDiagnostics.BridgeSnapshot captureMouseDiagnostics() {
        return mMouse.captureDiagnostics();
    }

    private boolean release() {
        mReadyDisplay = Display.INVALID_DISPLAY;
        boolean released = true;
        try {
            mMouse.stop();
        } finally {
            try {
                if (mRouting != null) {
                    mRouting.close();
                    mRouting = null;
                } else if (ShellAccess.isReady()) {
                    ShellAccess.cleanupInputRouting();
                }
            } catch (IOException error) {
                released = false;
                report("INPUT-ROUTING-003", "Could not restore input routes", error);
            }
        }
        mHandler.post(mChanged);
        return released;
    }

    private void report(final String code, final String title, final IOException error) {
        mError = ShellAccess.usefulMessage(error);
        InputSessionDiagnostics.noteFailure(error);
        CompatibilityDiagnostics.record(code, title, error.getMessage(), error);
    }
}
