package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps activity handoffs inside a task observed as freeform. */
final class ShellWindowedTaskActivityGuard implements
        ShellWindowedTaskLauncher.Listener,
        ShellActivityStartController.Listener {
    interface Listener {
        void onTaskCorrected(int taskId, String activityName);
        void onError(String message);
    }

    private static final String TAG = "MagicDeskWindowLaunch";
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int NEW_TASK_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;

    private final Object mService;
    private final Listener mListener;
    private final Map<Integer, TaskRecord> mTasks = new HashMap<>();
    private final List<Integer> mVisibleTaskOrder = new ArrayList<>();

    private ComponentName mInitialLaunchComponent;
    private int mFocusedTaskId = -1;
    private int mDisplayId = Display.INVALID_DISPLAY;

    ShellWindowedTaskActivityGuard(
            final Object service,
            final Listener listener) {
        mService = service;
        mListener = listener;
    }

    synchronized void configure(final int displayId) {
        if (mDisplayId == displayId) {
            return;
        }
        mDisplayId = displayId;
        mInitialLaunchComponent = null;
        mFocusedTaskId = -1;
        mTasks.clear();
        mVisibleTaskOrder.clear();
    }

    @Override
    public synchronized void onWindowedLaunchStarting(
            final ComponentName component) {
        mInitialLaunchComponent = component;
    }

    @Override
    public synchronized void onWindowedTaskIdentified(
            final int taskId,
            final ComponentName component,
            final int displayId,
            final Rect bounds) {
        if (displayId != mDisplayId
                || component == null
                || bounds == null
                || bounds.isEmpty()) {
            return;
        }
        mInitialLaunchComponent = null;
        mTasks.put(
                Integer.valueOf(taskId),
                new TaskRecord(taskId, component, displayId, bounds));
    }

    @Override
    public synchronized void onWindowedLaunchFinished(
            final ComponentName component) {
        if (component != null && component.equals(mInitialLaunchComponent)) {
            mInitialLaunchComponent = null;
        }
    }

    synchronized void onTaskRemoved(final int taskId) {
        mTasks.remove(Integer.valueOf(taskId));
        mVisibleTaskOrder.remove(Integer.valueOf(taskId));
        if (mFocusedTaskId == taskId) {
            mFocusedTaskId = -1;
        }
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            onTaskRemoved(taskId);
        }
    }

    synchronized void onTaskFocusChanged(
            final int taskId,
            final boolean focused) {
        if (focused && mTasks.containsKey(Integer.valueOf(taskId))) {
            mFocusedTaskId = taskId;
        } else if (!focused && mFocusedTaskId == taskId) {
            mFocusedTaskId = -1;
        }
    }

    @Override
    public synchronized boolean onActivityStarting(
            final Intent intent,
            final String packageName) {
        if (intent == null || mDisplayId == Display.INVALID_DISPLAY) {
            return true;
        }
        final ComponentName component = intent.getComponent();
        if (component != null && component.equals(mInitialLaunchComponent)) {
            return true;
        }
        if ((intent.getFlags() & NEW_TASK_FLAGS) != 0) {
            return true;
        }
        final String requestedPackage = component != null
                ? component.getPackageName()
                : PackageNameValidator.isSafe(intent.getPackage())
                        ? intent.getPackage() : packageName;
        if (!PackageNameValidator.isSafe(requestedPackage)) {
            return true;
        }
        TaskRecord target = findVisiblePackageTask(requestedPackage);
        if (target == null) {
            target = focusedVisibleTask();
        }
        if (target == null) {
            target = uniqueVisibleTask();
        }
        if (target == null) {
            return true;
        }
        target.activityState.arm(
                component == null ? null : component.flattenToShortString(),
                requestedPackage);
        Log.d(TAG, "armed activity handoff task=" + target.taskId
                + " activity=" + activityLabel(component, requestedPackage));
        return true;
    }

    void observeTasks(
            final int displayId,
            final List<ShellTaskStateMonitor.TaskWindowState> tasks) {
        if (displayId != configuredDisplayId() || tasks == null) {
            return;
        }
        final List<Correction> corrections = new ArrayList<>();
        synchronized (this) {
            if (displayId != mDisplayId) {
                return;
            }
            mVisibleTaskOrder.clear();
            for (final ShellTaskStateMonitor.TaskWindowState observation
                    : tasks) {
                if (!observation.visible) {
                    continue;
                }
                TaskRecord record = mTasks.get(
                        Integer.valueOf(observation.taskId));
                if (record == null
                        && observation.windowingMode
                                == WINDOWING_MODE_FREEFORM
                        && observation.rootComponent != null
                        && !observation.bounds.isEmpty()) {
                    record = new TaskRecord(
                            observation.taskId,
                            observation.rootComponent,
                            displayId,
                            observation.bounds);
                    mTasks.put(Integer.valueOf(observation.taskId), record);
                }
                if (record == null) {
                    continue;
                }
                mVisibleTaskOrder.add(Integer.valueOf(observation.taskId));
                if (observation.windowingMode == WINDOWING_MODE_FREEFORM
                        && !observation.bounds.isEmpty()) {
                    record.bounds.set(observation.bounds);
                }
                final String topComponent = observation.topComponent == null
                        ? null
                        : observation.topComponent.flattenToShortString();
                final String topPackage = observation.topComponent == null
                        ? null
                        : observation.topComponent.getPackageName();
                final WindowedTaskActivityState.Decision decision =
                        record.activityState.observe(
                                topComponent,
                                topPackage,
                                observation.windowingMode,
                                observation.requestingImmersive());
                if (decision
                        == WindowedTaskActivityState.Decision
                                .RESTORE_FREEFORM) {
                    corrections.add(new Correction(
                            record,
                            activityLabel(observation.topComponent,
                                    topPackage)));
                }
            }
        }
        for (final Correction correction : corrections) {
            restoreFreeform(correction);
        }
    }

    private void restoreFreeform(final Correction correction) {
        try {
            ShellPreparedTaskTransition.applyFreeform(
                    mService,
                    correction.record.displayId,
                    correction.record.taskId,
                    new Rect(correction.record.bounds));
            Log.i(TAG, "restored activity handoff task="
                    + correction.record.taskId
                    + " activity=" + correction.activityName);
            if (mListener != null) {
                mListener.onTaskCorrected(
                        correction.record.taskId, correction.activityName);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            synchronized (this) {
                correction.record.activityState.correctionFailed();
            }
            report("could not restore windowed activity handoff task="
                    + correction.record.taskId + ": "
                    + usefulMessage(error));
        }
    }

    private synchronized int configuredDisplayId() {
        return mDisplayId;
    }

    private TaskRecord findVisiblePackageTask(final String packageName) {
        final TaskRecord focused = focusedVisibleTask();
        if (focused != null && packageName.equals(
                focused.activityState.rootPackage())) {
            return focused;
        }
        for (final Integer taskId : mVisibleTaskOrder) {
            final TaskRecord record = mTasks.get(taskId);
            if (record != null
                    && packageName.equals(record.activityState.rootPackage())) {
                return record;
            }
        }
        TaskRecord unique = null;
        for (final TaskRecord record : mTasks.values()) {
            if (!packageName.equals(record.activityState.rootPackage())) {
                continue;
            }
            if (unique != null) {
                return null;
            }
            unique = record;
        }
        return unique;
    }

    private TaskRecord focusedVisibleTask() {
        final Integer focusedId = Integer.valueOf(mFocusedTaskId);
        return mVisibleTaskOrder.contains(focusedId)
                ? mTasks.get(focusedId) : null;
    }

    private TaskRecord uniqueVisibleTask() {
        TaskRecord unique = null;
        for (final Integer taskId : mVisibleTaskOrder) {
            final TaskRecord record = mTasks.get(taskId);
            if (record == null) {
                continue;
            }
            if (unique != null) {
                return null;
            }
            unique = record;
        }
        return unique;
    }

    private void report(final String message) {
        Log.w(TAG, message);
        if (mListener != null) {
            mListener.onError(message);
        }
    }

    private static String activityLabel(
            final ComponentName component,
            final String packageName) {
        return component == null
                ? String.valueOf(packageName)
                : component.flattenToShortString();
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    private static final class TaskRecord {
        final int taskId;
        final int displayId;
        final Rect bounds;
        final WindowedTaskActivityState activityState;

        TaskRecord(
                final int observedTaskId,
                final ComponentName component,
                final int targetDisplayId,
                final Rect initialBounds) {
            taskId = observedTaskId;
            displayId = targetDisplayId;
            bounds = new Rect(initialBounds);
            activityState = new WindowedTaskActivityState(
                    component.getPackageName());
        }
    }

    private static final class Correction {
        final TaskRecord record;
        final String activityName;

        Correction(
                final TaskRecord taskRecord,
                final String correctedActivityName) {
            record = taskRecord;
            activityName = correctedActivityName;
        }
    }
}
