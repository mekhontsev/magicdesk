package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/** Owns an organizer-created task display area and its Binder lifetime. */
final class TaskDisplayAreaHandle {
    private static final String TAG = "MagicDeskDisplayArea";

    enum Parent {
        ROOT(0),
        DEFAULT_TASK_CONTAINER(1);

        private final int mFeatureId;

        Parent(final int featureId) {
            mFeatureId = featureId;
        }

        int featureId() {
            return mFeatureId;
        }
    }

    private final Object mOrganizer;
    private final Object mToken;
    private final Object mLeash;
    private final int mFeatureId;
    private boolean mClosed;
    private boolean mLeashReleased;

    private TaskDisplayAreaHandle(
            final Object organizer,
            final Object token,
            final Object leash,
            final int featureId) {
        mOrganizer = organizer;
        mToken = token;
        mLeash = leash;
        mFeatureId = featureId;
    }

    static TaskDisplayAreaHandle create(
            final int displayId,
            final Parent parent,
            final String name) throws ReflectiveOperationException {
        return create(displayId, parent, name, false);
    }

    static TaskDisplayAreaHandle createSurfaceOrdered(
            final int displayId,
            final Parent parent,
            final String name) throws ReflectiveOperationException {
        return create(displayId, parent, name, true);
    }

    private static TaskDisplayAreaHandle create(
            final int displayId,
            final Parent parent,
            final String name,
            final boolean retainLeash) throws ReflectiveOperationException {
        if (parent == null) {
            throw new IllegalArgumentException(
                    "task display area requires a parent");
        }
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
                        Integer.valueOf(parent.featureId()),
                        name);
        final Object areaInfo = appeared.getClass()
                .getMethod("getDisplayAreaInfo")
                .invoke(appeared);
        final Object token = HiddenTaskApi.getContainerToken(areaInfo);
        final int featureId = HiddenTaskApi.getContainerFeatureId(areaInfo);
        final Object leash = appeared.getClass()
                .getMethod("getLeash")
                .invoke(appeared);
        if (!retainLeash) {
            releaseSurface(leash);
        }
        Log.i(TAG, "created task display area feature=" + featureId
                + " display=" + displayId + " name=" + name);
        return new TaskDisplayAreaHandle(
                organizer, token, retainLeash ? leash : null, featureId);
    }

    Object token() {
        return mToken;
    }

    int featureId() {
        return mFeatureId;
    }

    synchronized Object surfaceLeash() {
        if (mLeash == null || mLeashReleased) {
            throw new IllegalStateException(
                    "task display area has no retained surface leash");
        }
        return mLeash;
    }

    /** Assigns this organizer-owned area a stable sibling surface layer. */
    synchronized void setSurfaceLayer(final int layer)
            throws ReflectiveOperationException {
        final Class<?> surfaceClass = Class.forName(
                "android.view.SurfaceControl");
        final Class<?> transactionClass = Class.forName(
                "android.view.SurfaceControl$Transaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        try {
            transactionClass.getMethod(
                    "setLayer", surfaceClass, Integer.TYPE)
                    .invoke(transaction, surfaceLeash(), Integer.valueOf(layer));
            transactionClass.getMethod("apply").invoke(transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    /** Controls whether child activity requests may rotate this desktop area. */
    void setIgnoreOrientationRequest(
            final Object service,
            final boolean ignore) throws ReflectiveOperationException {
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        windowing.setIgnoreOrientationRequest(
                transaction, mToken, ignore);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, transaction);
    }

    /** Reparents any live child tasks before this organizer area is deleted. */
    void detachChildTasks(
            final Object service,
            final int displayId,
            final Collection<Integer> ownedTaskIds)
            throws ReflectiveOperationException {
        detachChildTasks(service, displayId, ownedTaskIds, null);
    }

    /** Reparents live child tasks to a specific parent or the default area. */
    void detachChildTasks(
            final Object service,
            final int displayId,
            final Collection<Integer> ownedTaskIds,
            final Object targetParentToken)
            throws ReflectiveOperationException {
        detachChildTasks(
                service,
                displayId,
                ownedTaskIds,
                targetParentToken,
                true);
    }

    /** Reparents live child tasks with explicit destination ordering. */
    void detachChildTasks(
            final Object service,
            final int displayId,
            final Collection<Integer> ownedTaskIds,
            final Object targetParentToken,
            final boolean onTop)
            throws ReflectiveOperationException {
        if (service == null || displayId < 0
                || ownedTaskIds == null || ownedTaskIds.isEmpty()) {
            return;
        }
        final List<Integer> childTaskIds = new ArrayList<>();
        final List<Object> childTaskTokens = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getTaskId(task));
            if (!ownedTaskIds.contains(taskId)
                    || HiddenTaskApi.getTaskDisplayAreaFeatureId(task) != mFeatureId) {
                continue;
            }
            childTaskIds.add(taskId);
            childTaskTokens.add(HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId.intValue()));
        }
        if (childTaskIds.isEmpty()) {
            return;
        }

        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        // Running tasks are returned top-first. Reparent bottom-first so their
        // relative z-order remains unchanged in the destination parent.
        for (int index = childTaskTokens.size() - 1; index >= 0; index--) {
            windowing.reparent(
                    transaction,
                    childTaskTokens.get(index),
                    targetParentToken,
                    onTop);
        }
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, transaction);
        Log.i(TAG, "detached tasks=" + childTaskIds
                + " from feature=" + mFeatureId);
    }

    synchronized boolean closeIfEmpty(
            final Object service,
            final int displayId) {
        return closeIfOnlyOwnedChildren(
                service,
                displayId,
                java.util.Collections.<Integer>emptySet());
    }

    /**
     * Deletes this area when its only remaining tasks are disposable
     * infrastructure owned by the caller.
     *
     * <p>Organizer-created task areas remove non-standard roots, such as a
     * HOME backstop, as part of their framework teardown. Keeping that work
     * inside {@code deleteTaskDisplayArea()} avoids an intermediate hierarchy
     * in which the child task is gone but its nested area is still attached.
     */
    synchronized boolean closeIfOnlyOwnedChildren(
            final Object service,
            final int displayId,
            final Collection<Integer> removableTaskIds) {
        if (mClosed) {
            return true;
        }
        try {
            final List<Integer> childTaskIds = childTaskIds(
                    service, displayId);
            if (removableTaskIds == null
                    || !removableTaskIds.containsAll(childTaskIds)) {
                Log.w(TAG, "refusing to remove task display area with"
                        + " unowned children feature=" + mFeatureId
                        + " tasks=" + childTaskIds);
                return false;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not verify task display area feature="
                    + mFeatureId, error);
            return false;
        }
        final Throwable directFailure;
        try {
            final Class<?> tokenClass = FrameworkRuntime.current()
                    .windowing().tokenClass();
            mOrganizer.getClass().getMethod(
                    "deleteTaskDisplayArea", tokenClass)
                    .invoke(mOrganizer, mToken);
            mClosed = true;
            releaseRetainedLeash();
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
            releaseRetainedLeash();
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

    private List<Integer> childTaskIds(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        if (service == null || displayId < 0) {
            throw new IllegalArgumentException(
                    "task display area inspection requires a display");
        }
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            if (HiddenTaskApi.getTaskDisplayAreaFeatureId(task) == mFeatureId) {
                taskIds.add(Integer.valueOf(
                        HiddenTaskApi.getTaskId(task)));
            }
        }
        return taskIds;
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
            releaseSurface(leash);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No caller retains the organizer surface handle.
        }
    }

    private synchronized void releaseRetainedLeash() {
        if (mLeash == null || mLeashReleased) {
            return;
        }
        releaseSurface(mLeash);
        mLeashReleased = true;
    }

    private static void releaseSurface(final Object leash) {
        if (leash == null) {
            return;
        }
        try {
            leash.getClass().getMethod("release").invoke(leash);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Releasing a local SurfaceControl handle is best-effort.
        }
    }
}
