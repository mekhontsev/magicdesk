package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/** Owns an organizer-created task display area and its Binder lifetime. */
final class TaskDisplayAreaHandle {
    private static final String TAG = "MagicDeskDisplayArea";

    private final Object mOrganizer;
    private final Object mToken;
    private final int mFeatureId;
    private boolean mClosed;

    private TaskDisplayAreaHandle(
            final Object organizer,
            final Object token,
            final int featureId) {
        mOrganizer = organizer;
        mToken = token;
        mFeatureId = featureId;
    }

    static TaskDisplayAreaHandle create(
            final int displayId,
            final int parentFeatureId,
            final String name) throws ReflectiveOperationException {
        final Class<?> organizerClass = Class.forName(
                "android.window.DisplayAreaOrganizer");
        final Executor directExecutor = Runnable::run;
        final Object organizer = organizerClass.getConstructor(Executor.class)
                .newInstance(directExecutor);
        final Object appeared = organizerClass.getMethod(
                "createTaskDisplayArea",
                Integer.TYPE,
                Integer.TYPE,
                String.class)
                .invoke(
                        organizer,
                        Integer.valueOf(displayId),
                        Integer.valueOf(parentFeatureId),
                        name);
        final Object areaInfo = appeared.getClass()
                .getMethod("getDisplayAreaInfo")
                .invoke(appeared);
        final Object token = HiddenTaskApi.getField(areaInfo, "token");
        final int featureId = HiddenTaskApi.getIntField(
                areaInfo, "featureId");
        releaseLeash(appeared);
        Log.i(TAG, "created task display area feature=" + featureId
                + " display=" + displayId + " name=" + name);
        return new TaskDisplayAreaHandle(organizer, token, featureId);
    }

    Object token() {
        return mToken;
    }

    int featureId() {
        return mFeatureId;
    }

    /** Reparents any live child tasks before this organizer area is deleted. */
    void detachChildTasks(
            final Object service,
            final int displayId,
            final Collection<Integer> ownedTaskIds)
            throws ReflectiveOperationException {
        if (service == null || displayId < 0
                || ownedTaskIds == null || ownedTaskIds.isEmpty()) {
            return;
        }
        final List<Integer> childTaskIds = new ArrayList<>();
        final List<Object> childTaskTokens = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getIntField(task, "taskId"));
            if (!ownedTaskIds.contains(taskId)) {
                continue;
            }
            if (HiddenTaskApi.getIntField(
                    task, "displayAreaFeatureId") == mFeatureId) {
                childTaskIds.add(taskId);
                childTaskTokens.add(HiddenTaskApi.requireTaskToken(
                        service, displayId, taskId.intValue()));
            }
        }
        if (childTaskIds.isEmpty()) {
            return;
        }

        final Class<?> tokenClass = Class.forName(
                "android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        // Running tasks are returned top-first. Reparent bottom-first so their
        // relative z-order remains unchanged in the default task area.
        for (int index = childTaskTokens.size() - 1; index >= 0; index--) {
            transactionClass.getMethod(
                    "reparent", tokenClass, tokenClass, Boolean.TYPE)
                    .invoke(transaction, new Object[]{
                            childTaskTokens.get(index), null, Boolean.TRUE});
        }
        SyncWindowContainerTransaction.apply(
                service, transactionClass, transaction);
        Log.i(TAG, "detached tasks=" + childTaskIds
                + " from feature=" + mFeatureId);
    }

    synchronized boolean closeIfEmpty(
            final Object service,
            final int displayId) {
        if (mClosed) {
            return true;
        }
        try {
            if (!isEmpty(service, displayId)) {
                Log.w(TAG, "refusing to remove non-empty task display area"
                        + " feature=" + mFeatureId);
                return false;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not verify task display area feature="
                    + mFeatureId, error);
            return false;
        }
        final Throwable directFailure;
        try {
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            mOrganizer.getClass().getMethod(
                    "deleteTaskDisplayArea", tokenClass)
                    .invoke(mOrganizer, mToken);
            mClosed = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            directFailure = error;
        }

        // Some vendor implementations clear the organizer before failing to
        // remove an empty area. Re-registering its unique runtime feature ID
        // restores ownership so unregister can complete the same framework
        // cleanup instead of leaving an empty area in the task hierarchy.
        try {
            recoverOrphanedArea();
            mClosed = true;
            Log.w(TAG, "recovered task display area feature=" + mFeatureId
                    + " after direct removal failed: "
                    + usefulMessage(directFailure));
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            error.addSuppressed(directFailure);
            Log.w(TAG, "cannot remove task display area feature="
                    + mFeatureId, error);
            return false;
        }
    }

    private boolean isEmpty(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        if (service == null || displayId < 0) {
            return false;
        }
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            if (HiddenTaskApi.getIntField(
                    task, "displayAreaFeatureId") == mFeatureId) {
                return false;
            }
        }
        return true;
    }

    private void recoverOrphanedArea() throws ReflectiveOperationException {
        final Class<?> organizerClass = Class.forName(
                "android.window.DisplayAreaOrganizer");
        final Executor directExecutor = Runnable::run;
        final Object organizer = organizerClass.getConstructor(Executor.class)
                .newInstance(directExecutor);
        final Object appeared = organizerClass.getMethod(
                "registerOrganizer", Integer.TYPE)
                .invoke(organizer, Integer.valueOf(mFeatureId));
        if (appeared instanceof List<?>) {
            for (final Object area : (List<?>) appeared) {
                releaseLeash(area);
            }
        }
        organizerClass.getMethod("unregisterOrganizer").invoke(organizer);
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

    private static void releaseLeash(final Object appeared) {
        try {
            final Object leash = appeared.getClass()
                    .getMethod("getLeash")
                    .invoke(appeared);
            if (leash != null) {
                leash.getClass().getMethod("release").invoke(leash);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No caller retains the organizer surface handle.
        }
    }
}
