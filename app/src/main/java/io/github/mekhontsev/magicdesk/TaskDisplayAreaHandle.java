package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.util.List;
import java.util.concurrent.Executor;

/** Owns an organizer-created task display area and its Binder lifetime. */
final class TaskDisplayAreaHandle implements AutoCloseable {
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

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        final Throwable directFailure;
        try {
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            mOrganizer.getClass().getMethod(
                    "deleteTaskDisplayArea", tokenClass)
                    .invoke(mOrganizer, mToken);
            return;
        } catch (ReflectiveOperationException | RuntimeException error) {
            directFailure = error;
        }

        // Some vendor implementations clear the organizer before failing to
        // remove an empty area. Re-registering its unique runtime feature ID
        // restores ownership so unregister can complete the same framework
        // cleanup instead of leaving an empty area in the task hierarchy.
        try {
            recoverOrphanedArea();
            Log.w(TAG, "recovered task display area feature=" + mFeatureId
                    + " after direct removal failed: "
                    + usefulMessage(directFailure));
        } catch (ReflectiveOperationException | RuntimeException error) {
            error.addSuppressed(directFailure);
            throw new IllegalStateException(
                    "cannot remove task display area feature=" + mFeatureId,
                    error);
        }
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
