package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.app.IActivityController;
import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;
import android.view.Display;

import java.io.Closeable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Prevents phone-side freeform state from destabilizing Nubia Quickstep. */
final class ShellExternalTaskMigrationGuard implements Closeable {
    interface Listener {
        void onError(String message);
        void onPhoneTaskNormalized(int taskId);
    }

    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int MAGICDESK_LAUNCH_FLAGS =
            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;

    private final Object mService;
    private final Listener mListener;
    private final Map<Integer, TaskState> mDesktopTasks = new HashMap<>();
    private final Set<Integer> mMigratingTasks = new HashSet<>();
    private final ExecutorService mMigrationExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDesk-phone-migration");
                thread.setDaemon(true);
                return thread;
            });
    private final IActivityController mActivityController =
            new IActivityController.Stub() {
                @Override
                public boolean activityStarting(
                        final Intent intent,
                        final String packageName) {
                    return interceptPhoneLaunch(intent, packageName);
                }

                @Override
                public boolean activityResuming(final String packageName) {
                    return true;
                }

                @Override
                public boolean appCrashed(
                        final String processName,
                        final int pid,
                        final String shortMessage,
                        final String longMessage,
                        final long timeMillis,
                        final String stackTrace) {
                    return true;
                }

                @Override
                public int appEarlyNotResponding(
                        final String processName,
                        final int pid,
                        final String annotation) {
                    return 0;
                }

                @Override
                public int appNotResponding(
                        final String processName,
                        final int pid,
                        final String processStats) {
                    return 0;
                }

                @Override
                public int systemNotResponding(final String message) {
                    return -1;
                }
            };

    private int mDisplayId = Display.INVALID_DISPLAY;
    private boolean mEnabled;
    private boolean mControllerRegistered;

    ShellExternalTaskMigrationGuard(
            final Object service,
            final Listener listener) {
        mService = service;
        mListener = listener;
    }

    synchronized void configure(
            final int displayId,
            final boolean enabled) {
        mDisplayId = enabled && displayId > Display.DEFAULT_DISPLAY
                ? displayId : Display.INVALID_DISPLAY;
        mEnabled = mDisplayId != Display.INVALID_DISPLAY;
        mDesktopTasks.clear();
        mMigratingTasks.clear();
        if (mEnabled) {
            captureDesktopTasks(mDisplayId);
            registerActivityController();
            schedulePhoneTaskScan();
        } else {
            unregisterActivityController();
        }
    }

    private void schedulePhoneTaskScan() {
        try {
            mMigrationExecutor.execute(this::onTaskStackChanged);
        } catch (RuntimeException error) {
            report("could not schedule initial phone task scan: "
                    + usefulMessage(error));
        }
    }

    void onTaskStackChanged() {
        synchronized (this) {
            if (!mEnabled) {
                return;
            }
        }
        try {
            for (final Object task : HiddenTaskApi.getTasks(
                    mService, Display.DEFAULT_DISPLAY)) {
                final int taskId = HiddenTaskApi.getIntField(task, "taskId");
                if (!normalizeObservedPhoneTask(
                        taskId, Display.DEFAULT_DISPLAY, task)) {
                    forget(taskId);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not inspect phone tasks: "
                    + usefulMessage(error));
        }
    }

    void onTaskMovedToFront(
            final ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo == null) {
            return;
        }
        final int displayId = HiddenTaskApi.getTaskDisplayId(taskInfo);
        if (isConfiguredFor(displayId)) {
            captureDesktopTask(taskInfo, displayId);
        } else if (normalizeObservedPhoneTask(
                taskInfo.taskId, displayId, taskInfo)) {
            // Keep transition ownership until fullscreen is committed.
        } else {
            forget(taskInfo.taskId);
        }
    }

    void onTaskDisplayChanged(
            final int taskId,
            final int newDisplayId) {
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, newDisplayId, taskId);
            if (isConfiguredFor(newDisplayId)) {
                if (task != null) {
                    captureDesktopTask(task, newDisplayId);
                }
                return;
            }
            if (task != null && normalizeObservedPhoneTask(
                    taskId, newDisplayId, task)) {
                return;
            }
            forget(taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not inspect task after display change: "
                    + usefulMessage(error));
        }
    }

    synchronized void forget(final int taskId) {
        mDesktopTasks.remove(Integer.valueOf(taskId));
        mMigratingTasks.remove(Integer.valueOf(taskId));
    }

    private void captureDesktopTasks(final int displayId) {
        try {
            final Map<Integer, TaskState> observed = new HashMap<>();
            for (final Object task : HiddenTaskApi.getTasks(
                    mService, displayId)) {
                final TaskState state = createTaskState(task);
                if (state != null) {
                    observed.put(Integer.valueOf(state.taskId), state);
                }
            }
            if (mEnabled && mDisplayId == displayId) {
                mDesktopTasks.putAll(observed);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not capture external desktop tasks: "
                    + usefulMessage(error));
        }
    }

    private void captureDesktopTask(
            final Object task,
            final int displayId) {
        try {
            final TaskState state = createTaskState(task);
            if (state == null) {
                return;
            }
            synchronized (this) {
                if (mEnabled && mDisplayId == displayId) {
                    mDesktopTasks.put(Integer.valueOf(state.taskId), state);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not capture external desktop task: "
                    + usefulMessage(error));
        }
    }

    private static TaskState createTaskState(final Object task)
            throws ReflectiveOperationException {
        if (HiddenTaskApi.getWindowConfigurationValue(
                task, "getActivityType") != ACTIVITY_TYPE_STANDARD
                || MAGICDESK_PACKAGE.equals(
                        HiddenTaskApi.getTaskPackage(task))) {
            return null;
        }
        final int taskId = HiddenTaskApi.getIntField(task, "taskId");
        final ComponentName component = findLaunchComponent(task);
        return component == null ? null : new TaskState(taskId, component);
    }

    private static ComponentName findLaunchComponent(final Object task) {
        try {
            final Object baseIntent =
                    HiddenTaskApi.getField(task, "baseIntent");
            if (baseIntent instanceof Intent) {
                final ComponentName component =
                        ((Intent) baseIntent).getComponent();
                if (component != null) {
                    return component;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older task-info variants still expose an activity component.
        }
        return HiddenTaskApi.getTaskComponent(task);
    }

    private boolean interceptPhoneLaunch(
            final Intent intent,
            final String packageName) {
        final TaskState state;
        synchronized (this) {
            state = findPhoneLaunchTarget(intent, packageName);
            if (state == null
                    || mMigratingTasks.contains(
                            Integer.valueOf(state.taskId))) {
                return true;
            }
            mMigratingTasks.add(Integer.valueOf(state.taskId));
        }
        try {
            mMigrationExecutor.execute(() -> migrateToPhone(state));
            Log.i(TAG, "intercepted launcher migration task=" + state.taskId
                    + " package=" + state.component.getPackageName());
            return false;
        } catch (RuntimeException error) {
            synchronized (this) {
                mMigratingTasks.remove(Integer.valueOf(state.taskId));
            }
            report("could not schedule phone migration for task "
                    + state.taskId + ": " + usefulMessage(error));
            return true;
        }
    }

    private TaskState findPhoneLaunchTarget(
            final Intent intent,
            final String packageName) {
        // Nubia invokes IActivityController with the original launcher intent;
        // NEW_TASK and RESET_TASK_IF_NEEDED are added only after this callback.
        if (!mEnabled || intent == null
                || !Intent.ACTION_MAIN.equals(intent.getAction())
                || intent.getCategories() == null
                || !intent.getCategories().contains(Intent.CATEGORY_LAUNCHER)
                || (intent.getFlags() & MAGICDESK_LAUNCH_FLAGS) != 0) {
            return null;
        }
        final ComponentName requestedComponent = intent.getComponent();
        final String requestedPackage = requestedComponent == null
                ? packageName : requestedComponent.getPackageName();
        TaskState packageMatch = null;
        for (final TaskState candidate : mDesktopTasks.values()) {
            if (requestedComponent != null
                    && requestedComponent.equals(candidate.component)) {
                return candidate;
            }
            if (requestedPackage != null
                    && requestedPackage.equals(
                            candidate.component.getPackageName())) {
                if (packageMatch != null) {
                    return null;
                }
                packageMatch = candidate;
            }
        }
        return packageMatch;
    }

    private void migrateToPhone(final TaskState state) {
        final int sourceDisplayId;
        synchronized (this) {
            if (!mEnabled
                    || !mMigratingTasks.contains(
                            Integer.valueOf(state.taskId))) {
                return;
            }
            sourceDisplayId = mDisplayId;
        }
        boolean migrated = false;
        try {
            HiddenTaskApi.requireTask(
                    mService, sourceDisplayId, state.taskId);
            mService.getClass().getMethod(
                    "moveRootTaskToDisplay", Integer.TYPE, Integer.TYPE)
                    .invoke(
                            mService,
                            Integer.valueOf(state.taskId),
                            Integer.valueOf(Display.DEFAULT_DISPLAY));
            TaskWindowingCommand.focusFullscreenTask(
                    mService, Display.DEFAULT_DISPLAY, state.taskId);
            migrated = true;
            Log.i(TAG, "migrated launcher task to phone task="
                    + state.taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not migrate launcher task " + state.taskId
                    + " to the phone: " + usefulMessage(error));
        } finally {
            synchronized (this) {
                mMigratingTasks.remove(Integer.valueOf(state.taskId));
                if (migrated) {
                    mDesktopTasks.remove(Integer.valueOf(state.taskId));
                }
            }
        }
    }

    private boolean normalizeObservedPhoneTask(
            final int taskId,
            final int displayId,
            final Object task) {
        if (displayId != Display.DEFAULT_DISPLAY) {
            return false;
        }
        final boolean freeform;
        try {
            freeform = HiddenTaskApi.getWindowConfigurationValue(
                    task, "getWindowingMode") == WINDOWING_MODE_FREEFORM;
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not inspect phone task " + taskId + ": "
                    + usefulMessage(error));
            return false;
        }
        synchronized (this) {
            if (!ExternalTaskMigrationPolicy.shouldNormalizeObservedTask(
                    displayId,
                    mEnabled,
                    freeform)) {
                return false;
            }
            // This task is no longer hosted by the external desktop. Remove
            // stale launch interception state before normalizing its phone
            // windowing mode; a later move back will capture it again.
            mDesktopTasks.remove(Integer.valueOf(taskId));
            if (mMigratingTasks.contains(Integer.valueOf(taskId))) {
                return true;
            }
            mMigratingTasks.add(Integer.valueOf(taskId));
        }
        // The TaskStackListener callback is the earliest reliable point at
        // which display 0 exposes this task. Normalize it before returning so
        // Quickstep cannot bind the transient freeform state.
        normalizePhoneTask(taskId);
        return true;
    }

    private void normalizePhoneTask(final int taskId) {
        synchronized (this) {
            if (!mEnabled
                    || !mMigratingTasks.contains(
                            Integer.valueOf(taskId))) {
                return;
            }
        }
        try {
            // Alt+Tab and other system task switches bypass activityStarting.
            // No task may remain freeform on display 0 during an external
            // session: Nubia Quickstep can crash and erase launcher state.
            final Object task = HiddenTaskApi.findTask(
                    mService, Display.DEFAULT_DISPLAY, taskId);
            if (task == null) {
                Log.i(TAG, "skipped stale phone normalization task="
                        + taskId);
                return;
            }
            final boolean normalized =
                    TaskWindowingCommand.normalizeFullscreenTask(
                            mService, task);
            Log.i(TAG, (normalized
                    ? "normalized phone task to fullscreen task="
                    : "phone task already fullscreen task=") + taskId);
            if (normalized && mListener != null) {
                mListener.onPhoneTaskNormalized(taskId);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not normalize phone task " + taskId
                    + ": " + usefulMessage(error));
        } finally {
            synchronized (this) {
                mMigratingTasks.remove(Integer.valueOf(taskId));
            }
        }
    }

    private synchronized boolean isConfiguredFor(final int displayId) {
        return mEnabled && mDisplayId == displayId;
    }

    private void registerActivityController() {
        if (mControllerRegistered) {
            return;
        }
        try {
            setActivityController(mActivityController);
            mControllerRegistered = true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not register phone-launch interceptor: "
                    + usefulMessage(error));
        }
    }

    private void unregisterActivityController() {
        if (!mControllerRegistered) {
            return;
        }
        mControllerRegistered = false;
        try {
            setActivityController(null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not unregister phone-launch interceptor: "
                    + usefulMessage(error));
        }
    }

    private void setActivityController(
            final IActivityController controller)
            throws ReflectiveOperationException {
        mService.getClass().getMethod(
                "setActivityController",
                IActivityController.class,
                Boolean.TYPE)
                .invoke(mService, controller, Boolean.FALSE);
    }

    private void report(final String message) {
        Log.w(TAG, message);
        if (mListener != null) {
            mListener.onError(message);
        }
    }

    @Override
    public void close() {
        configure(Display.INVALID_DISPLAY, false);
        mMigrationExecutor.shutdownNow();
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    private static final class TaskState {
        final int taskId;
        final ComponentName component;

        TaskState(
                final int taskId,
                final ComponentName component) {
            this.taskId = taskId;
            this.component = component;
        }
    }
}
