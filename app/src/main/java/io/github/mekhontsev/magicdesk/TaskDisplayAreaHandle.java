package io.github.mekhontsev.magicdesk;

import java.util.concurrent.Executor;

/** Owns an organizer-created task display area and its Binder lifetime. */
final class TaskDisplayAreaHandle implements AutoCloseable {
    private final Object mOrganizer;
    private final Object mToken;
    private boolean mClosed;

    private TaskDisplayAreaHandle(
            final Object organizer,
            final Object token) {
        mOrganizer = organizer;
        mToken = token;
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
        releaseLeash(appeared);
        return new TaskDisplayAreaHandle(organizer, token);
    }

    Object token() {
        return mToken;
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        try {
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            mOrganizer.getClass().getMethod(
                    "deleteTaskDisplayArea", tokenClass)
                    .invoke(mOrganizer, mToken);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Binder death also removes an organizer-owned task display area.
        }
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
