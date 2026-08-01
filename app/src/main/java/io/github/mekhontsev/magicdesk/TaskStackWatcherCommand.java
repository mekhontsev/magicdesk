package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Looper;
import android.view.WindowInsets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskStackWatcherCommand extends TaskStackListener {
    private final Object mActivityTaskManagerService;
    private final TaskStateMonitor mTaskStateMonitor;

    private TaskStackWatcherCommand(final Object activityTaskManagerService)
            throws ReflectiveOperationException {
        mActivityTaskManagerService = activityTaskManagerService;
        mTaskStateMonitor = new TaskStateMonitor(activityTaskManagerService);
    }

    @Override
    public void onTaskStackChanged() {
        signalChange();
    }

    @Override
    public void onTaskCreated(final int taskId, final ComponentName componentName) {
        signalChange();
    }

    @Override
    public void onTaskRemoved(final int taskId) {
        signalTaskGone(taskId);
        signalChange();
    }

    @Override
    public void onTaskMovedToFront(final ActivityManager.RunningTaskInfo taskInfo) {
        signalChange();
    }

    @Override
    public void onTaskMovedToBack(final ActivityManager.RunningTaskInfo taskInfo) {
        signalChange();
    }

    @Override
    public void onTaskDisplayChanged(final int taskId, final int newDisplayId) {
        signalChange();
    }

    @Override
    public void onTaskFocusChanged(final int taskId, final boolean focused) {
        signalChange();
    }

    public static void main(final String[] args) {
        try {
            final Object service = HiddenTaskApi.getService();
            final TaskStackWatcherCommand listener = new TaskStackWatcherCommand(service);
            if (args.length == 2 && "snapshot-immersive".equals(args[0])) {
                listener.mTaskStateMonitor.printImmersiveSnapshot(
                        parseNonNegative(args[1], "display id"));
                return;
            }
            final Class<?> listenerClass = Class.forName("android.app.ITaskStackListener");
            service.getClass().getMethod("registerTaskStackListener", listenerClass)
                    .invoke(service, listener);
            startCommandReader(listener);
            System.out.println("ready");
            System.out.flush();
            Looper.prepare();
            Looper.loop();
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("task stack watcher failed: " + cause);
            System.exit(1);
        }
    }

    private static void signalChange() {
        System.out.println("changed");
        System.out.flush();
    }

    private static void startCommandReader(final TaskStackWatcherCommand listener) {
        final Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.applyCommand(line);
                }
            } catch (IOException ignored) {
                // A closed pipe is the normal shutdown signal.
            }
            System.exit(0);
        }, "MagicDeskTaskStackParent");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyCommand(final String line) {
        final String[] arguments = line.trim().split("\\s+");
        if (arguments.length >= 4 && "focus-stack".equals(arguments[0])) {
            applyFocusStackCommand(arguments);
            return;
        }
        if (arguments.length == 1 && "pause-immersive".equals(arguments[0])) {
            mTaskStateMonitor.pauseImmersive();
            return;
        }
        if (arguments.length == 3 && "watch-task".equals(arguments[0])) {
            try {
                mTaskStateMonitor.watchTask(
                        parseNonNegative(arguments[1], "display id"),
                        parseNonNegative(arguments[2], "task id"));
            } catch (RuntimeException e) {
                System.err.println("invalid immersive watch command: " + line);
            }
            return;
        }
        if (arguments.length == 10
                && "watch-native-maximize".equals(arguments[0])) {
            try {
                mTaskStateMonitor.watchDisplayBounds(
                        parseNonNegative(arguments[1], "display id"),
                        parseBounds(arguments, 2),
                        parseBounds(arguments, 6));
            } catch (RuntimeException e) {
                System.err.println("invalid native maximize watch command: " + line);
            }
            return;
        }
        System.err.println("task stack watcher ignored command: " + line);
    }

    private void applyFocusStackCommand(final String[] arguments) {
        long sequence = -1;
        int appliedTaskCount = 0;
        final int[] taskIds = new int[arguments.length - 3];
        try {
            sequence = parseNonNegativeLong(arguments[1], "sequence");
            final int displayId =
                    parseNonNegative(arguments[2], "display id");
            for (int index = 3; index < arguments.length; index++) {
                taskIds[index - 3] = parseNonNegative(arguments[index], "task id");
            }
            for (int index = 0; index < taskIds.length; index++) {
                final int taskId = taskIds[index];
                if (HiddenTaskApi.findTask(
                        mActivityTaskManagerService, displayId, taskId) == null) {
                    if (index == taskIds.length - 1) {
                        throw new IllegalStateException(
                                "task " + taskId
                                        + " not found on display " + displayId);
                    }
                    System.err.println("task focus skipped stale task=" + taskId);
                    continue;
                }
                TaskControlCommand.setFocusedTask(
                        mActivityTaskManagerService, taskId);
                appliedTaskCount++;
            }
            if (appliedTaskCount == 0) {
                throw new IllegalStateException("no live tasks to focus");
            }
            signalFocusStackResult(true, sequence, appliedTaskCount);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            signalFocusStackResult(false, sequence, appliedTaskCount);
            System.err.println("task stack focus failed: " + cause);
        }
    }

    private static void signalFocusStackResult(final boolean success, final long sequence,
            final int taskCount) {
        System.out.println((success ? "focus-stack-applied " : "focus-stack-failed ")
                + sequence + " " + taskCount);
        System.out.flush();
    }

    private static int parseNonNegative(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }

    private static long parseNonNegativeLong(final String value, final String label) {
        final long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }

    private static Rect parseBounds(
            final String[] arguments,
            final int startIndex) {
        final Rect bounds = new Rect(
                Integer.parseInt(arguments[startIndex]),
                Integer.parseInt(arguments[startIndex + 1]),
                Integer.parseInt(arguments[startIndex + 2]),
                Integer.parseInt(arguments[startIndex + 3]));
        if (bounds.isEmpty()) {
            throw new IllegalArgumentException("empty bounds");
        }
        return bounds;
    }

    private static final class TaskStateMonitor {
        private static final long POLL_INTERVAL_MILLIS = 150;
        private static final int MAX_TASKS_TO_SCAN = 16;
        private static final int WINDOWING_MODE_FREEFORM = 5;

        private final Object mService;
        private final Field mRequestedVisibleTypes;
        private final Object mLock = new Object();
        private final Set<Integer> mFullscreenTasks = new HashSet<>();
        private final Set<Integer> mMaximizedTasks = new HashSet<>();

        private int mImmersiveDisplayId = -1;
        private int mTaskId = -1;
        private int mLastVisibleTypes;
        private boolean mHasLastVisibleTypes;
        private int mBoundsDisplayId = -1;
        private Rect mDisplayBounds = new Rect();
        private Rect mWorkAreaBounds = new Rect();

        TaskStateMonitor(final Object service) throws ReflectiveOperationException {
            mService = service;
            mRequestedVisibleTypes = Class.forName("android.app.TaskInfo")
                    .getField("requestedVisibleTypes");
            final Thread monitor = new Thread(this::runMonitor,
                    "MagicDeskTaskStateMonitor");
            monitor.setDaemon(true);
            monitor.start();
        }

        void watchTask(final int displayId, final int taskId) {
            synchronized (mLock) {
                if (mImmersiveDisplayId == displayId && mTaskId == taskId) {
                    return;
                }
                mImmersiveDisplayId = displayId;
                mTaskId = taskId;
                mHasLastVisibleTypes = false;
                mLock.notifyAll();
            }
        }

        void pauseImmersive() {
            synchronized (mLock) {
                mImmersiveDisplayId = -1;
                mTaskId = -1;
                mHasLastVisibleTypes = false;
                mLock.notifyAll();
            }
        }

        void watchDisplayBounds(
                final int displayId,
                final Rect displayBounds,
                final Rect workAreaBounds) {
            if (displayBounds == null || displayBounds.isEmpty()
                    || workAreaBounds == null || workAreaBounds.isEmpty()
                    || !displayBounds.contains(workAreaBounds)) {
                throw new IllegalArgumentException("empty display bounds");
            }
            synchronized (mLock) {
                if (mBoundsDisplayId == displayId
                        && mDisplayBounds.equals(displayBounds)
                        && mWorkAreaBounds.equals(workAreaBounds)) {
                    return;
                }
                mBoundsDisplayId = displayId;
                mDisplayBounds = new Rect(displayBounds);
                mWorkAreaBounds = new Rect(workAreaBounds);
                mFullscreenTasks.clear();
                mMaximizedTasks.clear();
                mLock.notifyAll();
            }
        }

        void printImmersiveSnapshot(final int displayId)
                throws ReflectiveOperationException {
            for (final Object task : loadTasks(displayId)) {
                final int taskId =
                        HiddenTaskApi.getIntField(task, "taskId");
                final int visibleTypes = mRequestedVisibleTypes.getInt(task);
                signalImmersiveRequest(taskId,
                        isRequestingImmersive(visibleTypes), visibleTypes, true);
            }
        }

        private void runMonitor() {
            boolean failureReported = false;
            while (true) {
                final int immersiveDisplayId;
                final int taskId;
                final int boundsDisplayId;
                final Rect displayBounds;
                final Rect workAreaBounds;
                synchronized (mLock) {
                    while ((mImmersiveDisplayId < 0 || mTaskId < 0)
                            && (mBoundsDisplayId < 0 || mDisplayBounds.isEmpty())) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    immersiveDisplayId = mImmersiveDisplayId;
                    taskId = mTaskId;
                    boundsDisplayId = mBoundsDisplayId;
                    displayBounds = new Rect(mDisplayBounds);
                    workAreaBounds = new Rect(mWorkAreaBounds);
                }
                try {
                    List<?> boundsTasks = null;
                    if (boundsDisplayId >= 0 && !displayBounds.isEmpty()) {
                        boundsTasks = loadTasks(boundsDisplayId);
                        publishNativeMaximizeTransitions(
                                boundsDisplayId, displayBounds, workAreaBounds, boundsTasks);
                    }
                    if (immersiveDisplayId >= 0 && taskId >= 0) {
                        final List<?> immersiveTasks =
                                immersiveDisplayId == boundsDisplayId
                                        ? boundsTasks : loadTasks(immersiveDisplayId);
                        publishImmersiveChange(immersiveDisplayId, taskId,
                                loadVisibleTypes(immersiveTasks, taskId));
                    }
                    failureReported = false;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    if (!failureReported) {
                        Throwable cause = e;
                        while (cause.getCause() != null && cause.getCause() != cause) {
                            cause = cause.getCause();
                        }
                        System.err.println("task state monitor failed: " + cause);
                        failureReported = true;
                    }
                }
                synchronized (mLock) {
                    if (immersiveDisplayId == mImmersiveDisplayId
                            && taskId == mTaskId
                            && boundsDisplayId == mBoundsDisplayId
                            && displayBounds.equals(mDisplayBounds)
                            && workAreaBounds.equals(mWorkAreaBounds)) {
                        try {
                            mLock.wait(POLL_INTERVAL_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }

        private List<?> loadTasks(final int displayId)
                throws ReflectiveOperationException {
            return HiddenTaskApi.getTasks(
                    mService, displayId, MAX_TASKS_TO_SCAN);
        }

        private Integer loadVisibleTypes(final List<?> tasks, final int taskId)
                throws ReflectiveOperationException {
            if (tasks == null) {
                return null;
            }
            for (final Object task : tasks) {
                final int candidateTaskId =
                        HiddenTaskApi.getIntField(task, "taskId");
                if (candidateTaskId == taskId) {
                    return Integer.valueOf(mRequestedVisibleTypes.getInt(task));
                }
            }
            return null;
        }

        private void publishImmersiveChange(final int displayId, final int taskId,
                final Integer visibleTypes) {
            if (visibleTypes == null) {
                return;
            }
            synchronized (mLock) {
                if (displayId != mImmersiveDisplayId || taskId != mTaskId) {
                    return;
                }
                if (!mHasLastVisibleTypes
                        || mLastVisibleTypes != visibleTypes.intValue()) {
                    final boolean initialSample = !mHasLastVisibleTypes;
                    mLastVisibleTypes = visibleTypes.intValue();
                    mHasLastVisibleTypes = true;
                    signalImmersiveRequest(taskId,
                            isRequestingImmersive(visibleTypes.intValue()),
                            visibleTypes.intValue(), initialSample);
                }
            }
        }

        private void publishNativeMaximizeTransitions(final int displayId,
                final Rect displayBounds, final Rect workAreaBounds,
                final List<?> tasks)
                throws ReflectiveOperationException {
            final Set<Integer> fullscreenTasks = new HashSet<>();
            final Set<Integer> maximizedTasks = new HashSet<>();
            for (final Object task : tasks) {
                if (!HiddenTaskApi.getBooleanField(task, "isVisible")) {
                    continue;
                }
                final Object windowConfiguration =
                        HiddenTaskApi.getWindowConfiguration(task);
                final int windowingMode =
                        HiddenTaskApi.getWindowConfigurationValue(
                                task, "getWindowingMode");
                if (windowingMode != WINDOWING_MODE_FREEFORM) {
                    continue;
                }
                final Rect bounds = (Rect) windowConfiguration.getClass()
                        .getMethod("getBounds").invoke(windowConfiguration);
                if (displayBounds.equals(bounds)) {
                    final Integer taskId = Integer.valueOf(
                            HiddenTaskApi.getIntField(task, "taskId"));
                    fullscreenTasks.add(taskId);
                    maximizedTasks.add(taskId);
                } else if (workAreaBounds.equals(bounds)) {
                    maximizedTasks.add(Integer.valueOf(
                            HiddenTaskApi.getIntField(task, "taskId")));
                }
            }
            synchronized (mLock) {
                if (displayId != mBoundsDisplayId
                        || !displayBounds.equals(mDisplayBounds)
                        || !workAreaBounds.equals(mWorkAreaBounds)) {
                    return;
                }
                boolean changed = false;
                for (final Integer fullscreenTask : fullscreenTasks) {
                    if (!mFullscreenTasks.contains(fullscreenTask)) {
                        System.out.println("native-maximize "
                                + fullscreenTask.intValue());
                        changed = true;
                    }
                }
                for (final Integer maximizedTask : mMaximizedTasks) {
                    if (!maximizedTasks.contains(maximizedTask)) {
                        System.out.println("native-maximize-exit "
                                + maximizedTask.intValue());
                        changed = true;
                    }
                }
                if (changed) {
                    System.out.flush();
                }
                mFullscreenTasks.clear();
                mFullscreenTasks.addAll(fullscreenTasks);
                mMaximizedTasks.clear();
                mMaximizedTasks.addAll(maximizedTasks);
            }
        }

        private static boolean isRequestingImmersive(final int requestedVisibleTypes) {
            return (requestedVisibleTypes & WindowInsets.Type.statusBars()) == 0;
        }

        private static void signalImmersiveRequest(final int taskId,
                final boolean requestingImmersive, final int requestedVisibleTypes,
                final boolean initialSample) {
            System.out.println("immersive-request " + taskId + " "
                    + (requestingImmersive ? 1 : 0) + " " + requestedVisibleTypes
                    + " " + (initialSample ? 1 : 0));
            System.out.flush();
        }
    }

    private static void signalTaskGone(final int taskId) {
        System.out.println("task-gone " + taskId);
        System.out.flush();
    }
}
