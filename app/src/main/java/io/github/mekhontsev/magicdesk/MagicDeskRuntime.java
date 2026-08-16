package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;

import java.lang.ref.WeakReference;

/** Stable process-local entry point for the optional runtime service. */
public final class MagicDeskRuntime {
    private static WeakReference<MagicDeskRuntimeBackend> sBackend =
            new WeakReference<>(null);

    private MagicDeskRuntime() {
    }

    public static void start(final Context context) {
        context.startForegroundService(
                new Intent(context, MagicDeskRuntimeService.class));
    }

    public static void stop(final Context context) {
        context.stopService(
                new Intent(context, MagicDeskRuntimeService.class));
    }

    public static void refreshNotification() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.refreshNotification();
        }
    }

    static void setOperationStatus(final String status) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.setOperationStatus(status);
        }
    }

    static void refreshDesktopTasks() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.refreshDesktopTasks();
        }
    }

    static void refreshSettings() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.refreshSettings();
        }
    }

    static boolean isSessionWakeLockHeld() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.isSessionWakeLockHeld();
    }

    static void reconcileFailedDesktopLaunch(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.reconcileFailedDesktopLaunch(displayId);
        }
    }

    static void scheduleLocalDesktopCleanup() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.scheduleLocalDesktopCleanup();
        }
    }

    static boolean isDesktopMouseBridgeReady() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.isDesktopMouseBridgeReady();
    }

    static boolean capturePointerPosition() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.capturePointerPosition();
    }

    static void restorePointerPositionOnNextMotion() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.restorePointerPositionOnNextMotion();
        }
    }

    static Point getDesktopPointerPosition(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend == null
                ? null : backend.getDesktopPointerPosition(displayId);
    }

    static boolean updateDesktopPointerPosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.updateDesktopPointerPosition(
                displayId, x, y, action, downTime);
    }

    static boolean activateDesktopPointer(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.activateDesktopPointer(displayId);
    }

    static boolean clickDesktopPointer(
            final int displayId, final int button) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null
                && backend.clickDesktopPointer(displayId, button);
    }

    static boolean scrollDesktopPointer(
            final int displayId, final float amount) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null
                && backend.scrollDesktopPointer(displayId, amount);
    }

    static boolean updateDesktopTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.updateDesktopTextInput(
                displayId, action, text, arg1, arg2, arg3);
    }

    static boolean beginDesktopTextInput(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.beginDesktopTextInput(displayId);
    }

    static void endDesktopTextInput(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.endDesktopTextInput(displayId);
        }
    }

    static boolean showStart() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.showStart();
    }

    static synchronized void attach(
            final MagicDeskRuntimeBackend backend) {
        if (backend != null) {
            sBackend = new WeakReference<>(backend);
        }
    }

    static synchronized void detach(
            final MagicDeskRuntimeBackend backend) {
        if (sBackend.get() == backend) {
            sBackend.clear();
        }
    }

    private static synchronized MagicDeskRuntimeBackend backend() {
        final MagicDeskRuntimeBackend backend = sBackend.get();
        return backend != null && backend.isAvailable() ? backend : null;
    }
}
