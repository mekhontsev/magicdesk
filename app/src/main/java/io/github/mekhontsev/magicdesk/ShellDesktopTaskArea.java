package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;

/** Owns freeform tasks that must stay outside a display's default task area. */
final class ShellDesktopTaskArea implements AutoCloseable {
    private static final String TAG = "MagicDeskDesktopArea";
    private static final String HOST_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final String HOST_CLASS = HOST_PACKAGE
            + ".DesktopActivity";
    private static final int FEATURE_ROOT = 0;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long HIERARCHY_TIMEOUT_MILLIS = 3_000L;
    private static final long HIERARCHY_POLL_MILLIS = 20L;

    private final Object mService;
    private final ShellDesktopTaskOwnership mOwnership;
    private final Set<Integer> mTaskIds = new LinkedHashSet<>();

    private TaskDisplayAreaHandle mArea;
    private int mDisplayId = -1;
    private int mHostTaskId = -1;
    private boolean mEnabled;

    ShellDesktopTaskArea(
            final Object service,
            final ShellDesktopTaskOwnership ownership) {
        mService = service;
        mOwnership = ownership;
    }

    synchronized void configure(
            final int displayId,
            final boolean enabled,
            final int hostTaskId) {
        if (enabled && (displayId < 0 || hostTaskId < 0)) {
            throw new IllegalArgumentException(
                    "managed task area requires a display and host task");
        }
        if (mEnabled == enabled
                && (!enabled || (mDisplayId == displayId
                        && mHostTaskId == hostTaskId))) {
            return;
        }
        releaseTasks();
        if (!enabled) {
            mEnabled = false;
            mDisplayId = -1;
            return;
        }
        mEnabled = true;
        mDisplayId = displayId;
        try {
            ensureArea();
            attachHost(hostTaskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            releaseTasks();
            mEnabled = false;
            mDisplayId = -1;
            throw new IllegalStateException(
                    "cannot prepare desktop task area", error);
        }
    }

    synchronized int launchHost(
            final int displayId,
            final String intentUri) throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException(
                    "desktop host requires a display");
        }
        releaseTasks();
        mEnabled = true;
        mDisplayId = displayId;
        try {
            ensureArea();
            final Intent intent = TaskDisplayAreaLaunchCommand.createAppIntent(
                    intentUri);
            if (!HOST_PACKAGE.equals(
                    intent.getComponent().getPackageName())
                    || !HOST_CLASS.equals(
                            intent.getComponent().getClassName())) {
                throw new IllegalArgumentException(
                        "invalid desktop host component");
            }
            final int taskId = TaskDisplayAreaLaunchCommand
                    .launchFullscreenTask(
                            mService,
                            displayId,
                            intent,
                            intent.getComponent().getPackageName(),
                            Class.forName(
                                    "android.window.WindowContainerToken"),
                            mArea.token());
            mOwnership.markDesktop(taskId);
            attachHost(taskId);
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            releaseTasks();
            mEnabled = false;
            mDisplayId = -1;
            throw error;
        }
    }

    synchronized int launch(
            final int displayId,
            final String intentUri,
            final Rect bounds) throws ReflectiveOperationException {
        requireConfigured(displayId, bounds);
        ensureArea();
        final Intent intent = TaskDisplayAreaLaunchCommand.createAppIntent(
                intentUri);
        final int taskId = TaskDisplayAreaLaunchCommand.launchTask(
                mService,
                displayId,
                intent,
                intent.getComponent().getPackageName(),
                bounds,
                Class.forName("android.window.WindowContainerToken"),
                mArea.token(),
                false);
        mTaskIds.add(Integer.valueOf(taskId));
        mOwnership.markDesktop(taskId);
        waitForTaskArea(taskId, mArea.featureId(), true);
        TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                mService, displayId, taskId, bounds);
        return taskId;
    }

    synchronized void placeTask(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws ReflectiveOperationException {
        requireConfigured(targetDisplayId, bounds);
        ensureArea();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                mService, sourceDisplayId, taskId);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Integer.valueOf(WINDOWING_MODE_FREEFORM));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, new Rect(bounds));
        transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        transactionClass.getMethod(
                "reparent", tokenClass, tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, mArea.token(), Boolean.TRUE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass,
                transaction,
                tokenClass,
                taskToken,
                false);
        // This transition is explicitly owned by MagicDesk. Mark it before
        // WMShell publishes the resulting fullscreen-to-freeform change.
        mOwnership.markDesktop(taskId);
        TaskFullscreenTransitionCommand.startTransition(
                transactionClass, transaction);
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, mArea.featureId(), true);
        TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                mService, targetDisplayId, taskId, bounds);
    }

    synchronized void onTaskRemoved(final int taskId) {
        mTaskIds.remove(Integer.valueOf(taskId));
        if (taskId == mHostTaskId) {
            mHostTaskId = -1;
        }
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            onTaskRemoved(taskId);
        }
    }

    synchronized Boolean foregroundForTask(
            final int displayId,
            final int taskId) {
        if (!mEnabled || displayId != mDisplayId || taskId < 0) {
            return null;
        }
        return Boolean.valueOf(taskId == mHostTaskId
                || mTaskIds.contains(Integer.valueOf(taskId)));
    }

    @Override
    public synchronized void close() {
        releaseTasks();
        mEnabled = false;
        mDisplayId = -1;
    }

    private void requireConfigured(
            final int displayId,
            final Rect bounds) {
        if (!mEnabled || displayId != mDisplayId) {
            throw new IllegalStateException(
                    "desktop task area is not configured for display "
                            + displayId);
        }
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException("invalid task bounds");
        }
        if (mHostTaskId < 0) {
            throw new IllegalStateException(
                    "desktop task area has no host task");
        }
    }

    private void ensureArea() throws ReflectiveOperationException {
        if (mArea == null) {
            mArea = TaskDisplayAreaHandle.create(
                    mDisplayId, FEATURE_ROOT, "MagicDesk desktop session");
            Log.i(TAG, "created desktop task area display=" + mDisplayId);
        }
    }

    private void attachHost(final int hostTaskId)
            throws ReflectiveOperationException {
        ensureArea();
        // Track the task before changing hierarchy so cleanup can recover it
        // even when a later transaction operation fails.
        mHostTaskId = hostTaskId;
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        final Object hostToken = HiddenTaskApi.requireTaskToken(
                mService, mDisplayId, hostTaskId);
        transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        hostToken,
                        Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, hostToken, new Rect());
        transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE)
                .invoke(transaction, hostToken, Boolean.FALSE);
        transactionClass.getMethod(
                "reparent", tokenClass, tokenClass, Boolean.TYPE)
                .invoke(transaction, hostToken, mArea.token(), Boolean.FALSE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass,
                transaction,
                tokenClass,
                hostToken,
                true);
        // Keep the complete session plane above the default task area. This
        // must remain the final hierarchy operation in the transaction.
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, mArea.token(), Boolean.TRUE);
        SyncWindowContainerTransaction.applyAsync(
                mService, transactionClass, transaction);
        waitForTaskArea(hostTaskId, mArea.featureId(), true);
        Log.i(TAG, "attached desktop host task=" + hostTaskId
                + " display=" + mDisplayId);
    }

    private void releaseTasks() {
        final TaskDisplayAreaHandle area = mArea;
        if (area == null) {
            mTaskIds.clear();
            mHostTaskId = -1;
            return;
        }
        try {
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass = Class.forName(
                    "android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            boolean hasTasks = false;
            final Set<Integer> taskIds = new LinkedHashSet<>(mTaskIds);
            if (mHostTaskId >= 0) {
                taskIds.add(Integer.valueOf(mHostTaskId));
            }
            for (final Integer taskId : taskIds) {
                final Object task = HiddenTaskApi.findTask(
                        mService, mDisplayId, taskId.intValue());
                if (task == null) {
                    continue;
                }
                final Object taskToken = HiddenTaskApi.requireTaskToken(
                        mService, mDisplayId, taskId.intValue());
                transactionClass.getMethod(
                        "setWindowingMode", tokenClass, Integer.TYPE)
                        .invoke(
                                transaction,
                                taskToken,
                                Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
                transactionClass.getMethod(
                        "setBounds", tokenClass, Rect.class)
                        .invoke(transaction, taskToken, new Rect());
                transactionClass.getMethod(
                        "setForceTranslucent", tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, Boolean.FALSE);
                transactionClass.getMethod(
                        "reparent", tokenClass, tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, null, Boolean.FALSE);
                TaskCaptionInsetsCommand.addCaptionInsetOperation(
                        transactionClass,
                        transaction,
                        tokenClass,
                        taskToken,
                        true);
                hasTasks = true;
            }
            if (hasTasks) {
                SyncWindowContainerTransaction.applyAsync(
                        mService, transactionClass, transaction);
                waitForTasksOutsideArea(taskIds, area.featureId());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not release desktop task area", error);
        } finally {
            mArea = null;
            mTaskIds.clear();
            mHostTaskId = -1;
            area.close();
        }
    }

    private void waitForTaskArea(
            final int taskId,
            final int featureId,
            final boolean expectedInside)
            throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + HIERARCHY_TIMEOUT_MILLIS;
        int observedFeatureId = Integer.MIN_VALUE;
        do {
            final Object task = HiddenTaskApi.findTask(
                    mService, mDisplayId, taskId);
            if (task == null) {
                if (!expectedInside) {
                    return;
                }
            } else {
                observedFeatureId = HiddenTaskApi.getIntField(
                        task, "displayAreaFeatureId");
                if ((observedFeatureId == featureId) == expectedInside) {
                    return;
                }
            }
            SystemClock.sleep(HIERARCHY_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "task " + taskId + " did not reach display area "
                        + featureId + "; observed=" + observedFeatureId);
    }

    private void waitForTasksOutsideArea(
            final Set<Integer> taskIds,
            final int featureId) throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + HIERARCHY_TIMEOUT_MILLIS;
        do {
            boolean allOutside = true;
            for (final Integer taskId : taskIds) {
                final Object task = HiddenTaskApi.findTask(
                        mService, mDisplayId, taskId.intValue());
                if (task != null
                        && HiddenTaskApi.getIntField(
                                task, "displayAreaFeatureId") == featureId) {
                    allOutside = false;
                    break;
                }
            }
            if (allOutside) {
                return;
            }
            SystemClock.sleep(HIERARCHY_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "desktop tasks did not leave display area " + featureId);
    }
}
