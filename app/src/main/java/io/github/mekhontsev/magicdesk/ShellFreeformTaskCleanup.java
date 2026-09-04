package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes orphaned phone Recents entries for freeform tasks observed during
 * a local desktop session.
 */
final class ShellFreeformTaskCleanup implements Closeable {
    interface Listener {
        void onError(String error);
    }

    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final Object mService;
    private final Listener mListener;
    private final Map<Integer, Record> mObserved = new HashMap<>();

    private int mDisplayId = -1;
    private boolean mClosed;
    private boolean mDisabled;

    ShellFreeformTaskCleanup(final Object service, final Listener listener) {
        mService = service;
        mListener = listener;
    }

    synchronized void configure(final int displayId) {
        if (mClosed || displayId == mDisplayId) {
            return;
        }
        mDisplayId = displayId;
        mObserved.clear();
    }

    synchronized void observeTasks(final int displayId, final List<?> tasks) {
        if (mClosed || mDisabled || displayId != mDisplayId) {
            return;
        }
        try {
            final Map<Integer, Record> current = collectFreeformTasks(
                    displayId, tasks);
            final Map<Integer, Record> disappeared = new HashMap<>(mObserved);
            disappeared.keySet().removeAll(current.keySet());
            if (!disappeared.isEmpty()) {
                reconcileDisappeared(current, disappeared);
            }
            mObserved.clear();
            mObserved.putAll(current);
        } catch (ReflectiveOperationException | RuntimeException error) {
            final String message = usefulMessage(error);
            mDisabled = true;
            mObserved.clear();
            Log.w(TAG, "freeform task cleanup disabled: " + message, error);
            mListener.onError("freeform task cleanup disabled: " + message);
        }
    }

    @Override
    public synchronized void close() {
        mClosed = true;
        mDisplayId = -1;
        mObserved.clear();
    }

    private void reconcileDisappeared(
            final Map<Integer, Record> current,
            final Map<Integer, Record> disappeared)
            throws ReflectiveOperationException {
        final Map<Integer, Object> liveTasks = indexTasks(
                HiddenTaskApi.getAllTasks(mService));
        final Map<Integer, Object> recentTasks = indexTasks(
                HiddenTaskApi.getRecentTasks(mService));
        for (final Record record : disappeared.values()) {
            final Object liveTask = liveTasks.get(Integer.valueOf(record.taskId));
            final Object recentTask = recentTasks.get(Integer.valueOf(record.taskId));
            final FreeformTaskCleanupPolicy.Action action = decide(
                    record, liveTask, recentTask);
            if (action == FreeformTaskCleanupPolicy.Action.KEEP) {
                current.put(Integer.valueOf(record.taskId), record);
            } else if (action == FreeformTaskCleanupPolicy.Action.REMOVE_RECENT) {
                final boolean removed = TaskControlCommand.removeTask(
                        mService, record.taskId);
                Log.i(TAG, "removed orphaned freeform task=" + record.taskId
                        + " package=" + record.packageName
                        + " display=" + record.displayId
                        + " result=" + removed);
            }
        }
    }

    private static Map<Integer, Record> collectFreeformTasks(
            final int displayId, final List<?> tasks)
            throws ReflectiveOperationException {
        final Map<Integer, Record> result = new HashMap<>();
        if (tasks == null) {
            return result;
        }
        for (final Object task : tasks) {
            if (HiddenTaskApi.getTaskDisplayId(task) != displayId
                    || HiddenTaskApi.getTaskActivityType(task)
                            != FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD
                    || HiddenTaskApi.getTaskWindowingMode(task)
                            != WINDOWING_MODE_FREEFORM) {
                continue;
            }
            final String packageName = HiddenTaskApi.getTaskPackage(task);
            if (!PackageNameValidator.isSafe(packageName)
                    || MAGICDESK_PACKAGE.equals(packageName)) {
                continue;
            }
            final int taskId = HiddenTaskApi.getTaskId(task);
            result.put(Integer.valueOf(taskId),
                    new Record(taskId, packageName, displayId));
        }
        return result;
    }

    private static FreeformTaskCleanupPolicy.Action decide(
            final Record record,
            final Object liveTask,
            final Object recentTask) throws ReflectiveOperationException {
        final String livePackage = liveTask == null
                ? null : HiddenTaskApi.getTaskPackage(liveTask);
        final int liveDisplayId = liveTask == null
                ? -1 : HiddenTaskApi.getTaskDisplayId(liveTask);
        final boolean liveFreeform = liveTask != null
                && HiddenTaskApi.getTaskWindowingMode(liveTask) == WINDOWING_MODE_FREEFORM;
        final String recentPackage = recentTask == null
                ? null : HiddenTaskApi.getTaskPackage(recentTask);
        final int recentDisplayId = recentTask == null
                ? -1 : HiddenTaskApi.getTaskDisplayId(recentTask);
        return FreeformTaskCleanupPolicy.decide(
                record.packageName,
                record.displayId,
                liveTask != null,
                livePackage,
                liveDisplayId,
                liveFreeform,
                recentTask != null,
                recentPackage,
                recentDisplayId);
    }

    private static Map<Integer, Object> indexTasks(final List<?> tasks)
            throws ReflectiveOperationException {
        final Map<Integer, Object> result = new HashMap<>();
        for (final Object task : tasks) {
            result.put(Integer.valueOf(
                    HiddenTaskApi.getTaskId(task)), task);
        }
        return result;
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

    private static final class Record {
        final int taskId;
        final String packageName;
        final int displayId;

        Record(final int taskId, final String packageName, final int displayId) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.displayId = displayId;
        }
    }
}
