package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps true fullscreen tasks in a fullscreen parent while their order changes.
 *
 * <p>The dedicated parent is the invariant: reordering the same tasks in the
 * default desktop task area lets some firmware resolve them as freeform. Do
 * not replace this with a delayed fullscreen repair. See
 * {@code docs/fullscreen-alt-tab.md}.
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class ShellFullscreenTaskArea implements AutoCloseable {
    private static final String TAG = "MagicDeskFullscreenArea";
    private static final int FEATURE_ROOT = 0;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    private final Set<Integer> mTaskIds = new HashSet<>();

    private TaskDisplayAreaHandle mArea;
    private int mDisplayId = -1;

    synchronized boolean focusStack(
            final Object service,
            final int displayId,
            final int[] taskIds) {
        try {
            if (!isFullscreenStack(service, displayId, taskIds)) {
                if (!mTaskIds.isEmpty()) {
                    close();
                }
                return false;
            }
            ensureArea(service, displayId);
            applyFocus(service, displayId, taskIds);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "fullscreen task area unavailable", error);
            close();
            return false;
        }
    }

    private void applyFocus(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        final Object areaToken = mArea.token();
        for (final int taskId : taskIds) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            if (mTaskIds.add(Integer.valueOf(taskId))) {
                transactionClass.getMethod(
                        "reparent", tokenClass, tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, areaToken, Boolean.TRUE);
            } else {
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, Boolean.TRUE);
            }
        }
        SyncWindowContainerTransaction.apply(
                service, transactionClass, transaction);
    }

    private boolean isFullscreenStack(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        if (taskIds == null || taskIds.length < 2) {
            return false;
        }
        for (final int taskId : taskIds) {
            final Object task = HiddenTaskApi.findTask(
                    service, displayId, taskId);
            if (task == null
                    || HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            != WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
        }
        return true;
    }

    private void ensureArea(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        if (mArea != null && mDisplayId == displayId) {
            return;
        }
        close();

        final TaskDisplayAreaHandle area = TaskDisplayAreaHandle.create(
                displayId, FEATURE_ROOT, "MagicDesk fullscreen stack");
        final Object areaToken = area.token();
        try {
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass =
                    Class.forName("android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, areaToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            SyncWindowContainerTransaction.apply(
                    service, transactionClass, transaction);
        } catch (ReflectiveOperationException | RuntimeException error) {
            area.close();
            throw error;
        }

        mArea = area;
        mDisplayId = displayId;
        Log.i(TAG, "created fullscreen task area display=" + displayId);
    }

    synchronized void onWindowingModeChanged(
            final int displayId,
            final int taskId,
            final int windowingMode) {
        if (displayId == mDisplayId
                && mTaskIds.contains(Integer.valueOf(taskId))
                && windowingMode != WINDOWING_MODE_FULLSCREEN) {
            close();
        }
    }

    synchronized void onTaskRemoved(final int taskId) {
        if (mTaskIds.remove(Integer.valueOf(taskId)) && mTaskIds.isEmpty()) {
            close();
        }
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            onTaskRemoved(taskId);
        }
    }

    synchronized void configure(final int displayId) {
        if (mDisplayId >= 0 && displayId != mDisplayId) {
            close();
        }
    }

    @Override
    public synchronized void close() {
        final TaskDisplayAreaHandle area = mArea;
        mArea = null;
        mDisplayId = -1;
        mTaskIds.clear();
        if (area != null) {
            area.close();
        }
    }
}
