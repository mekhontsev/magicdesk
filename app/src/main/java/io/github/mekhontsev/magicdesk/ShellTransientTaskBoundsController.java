package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.graphics.Rect;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps a task's native desktop geometry while Android shows an in-task resolver. */
final class ShellTransientTaskBoundsController {
    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int ACTIVITY_TYPE_HOME = 2;

    private final Object mService;
    private final Map<Integer, Rect> mStableBounds = new HashMap<>();
    private final Map<Integer, Rect> mPendingBounds = new HashMap<>();

    private int mDisplayId = -1;

    ShellTransientTaskBoundsController(final Object service) {
        mService = service;
    }

    synchronized void configure(final int displayId) {
        if (mDisplayId != displayId) {
            mDisplayId = displayId;
            mStableBounds.clear();
            mPendingBounds.clear();
        }
        inspectForegroundTask();
    }

    synchronized void onTasksChanged() {
        inspectForegroundTask();
    }

    synchronized void forget(final int taskId) {
        final Integer key = Integer.valueOf(taskId);
        mStableBounds.remove(key);
        mPendingBounds.remove(key);
    }

    synchronized void close() {
        mDisplayId = -1;
        mStableBounds.clear();
        mPendingBounds.clear();
    }

    private void inspectForegroundTask() {
        if (mDisplayId < 0) {
            return;
        }
        try {
            final List<?> tasks = HiddenTaskApi.getTasks(mService, mDisplayId, 1);
            if (tasks.isEmpty()) {
                return;
            }
            inspect(tasks.get(0));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "transient task bounds inspection failed", error);
        }
    }

    private void inspect(final Object task) throws ReflectiveOperationException {
        if (!HiddenTaskApi.getBooleanField(task, "isVisible")
                || HiddenTaskApi.getWindowConfigurationValue(
                        task, "getWindowingMode") != WINDOWING_MODE_FREEFORM
                || HiddenTaskApi.getWindowConfigurationValue(
                        task, "getActivityType") == ACTIVITY_TYPE_HOME) {
            return;
        }

        final ComponentName baseActivity = (ComponentName) HiddenTaskApi.getField(
                task, "baseActivity");
        final ComponentName topActivity = (ComponentName) HiddenTaskApi.getField(
                task, "topActivity");
        if (baseActivity == null || topActivity == null
                || MAGICDESK_PACKAGE.equals(baseActivity.getPackageName())) {
            return;
        }

        final int taskId = HiddenTaskApi.getIntField(task, "taskId");
        final Integer key = Integer.valueOf(taskId);
        final Rect currentBounds = getBounds(task);
        if (currentBounds.isEmpty()) {
            return;
        }

        if (baseActivity.getPackageName().equals(topActivity.getPackageName())) {
            mStableBounds.put(key, currentBounds);
            mPendingBounds.remove(key);
            return;
        }

        final Rect stableBounds = mStableBounds.get(key);
        if (stableBounds == null || stableBounds.isEmpty()
                || currentBounds.equals(stableBounds)) {
            mPendingBounds.remove(key);
            return;
        }
        final Rect pendingBounds = mPendingBounds.get(key);
        if (stableBounds.equals(pendingBounds)) {
            return;
        }

        applyBounds(task, stableBounds);
        mPendingBounds.put(key, new Rect(stableBounds));
        Log.d(TAG, "preserved transient task bounds task=" + taskId
                + " current=" + currentBounds
                + " stable=" + stableBounds
                + " top=" + topActivity.flattenToShortString());
    }

    private static Rect getBounds(final Object task)
            throws ReflectiveOperationException {
        final Object windowConfiguration = HiddenTaskApi.getWindowConfiguration(task);
        final Rect bounds = (Rect) windowConfiguration.getClass()
                .getMethod("getBounds")
                .invoke(windowConfiguration);
        return bounds == null ? new Rect() : new Rect(bounds);
    }

    private void applyBounds(final Object task, final Rect bounds)
            throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.getField(task, "token");
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        final Method setBounds = transactionClass.getMethod(
                "setBounds", tokenClass, Rect.class);
        setBounds.invoke(transaction, taskToken, new Rect(bounds));
        final Object controller = mService.getClass()
                .getMethod("getWindowOrganizerController")
                .invoke(mService);
        controller.getClass()
                .getMethod("applyTransaction", transactionClass)
                .invoke(controller, transaction);
    }
}
