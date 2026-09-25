package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.IBinder;

/** Persistent empty Tasks, without taking task organization away from WMShell. */
final class FrameworkRootTaskApi {
    private FrameworkRootTaskApi() { }

    static final class Root {
        final int taskId;
        final Object token;
        private final Object organizer;
        private final IBinder cookie;

        private Root(Object organizer, IBinder cookie, Object task)
                throws ReflectiveOperationException {
            this.organizer = organizer;
            this.cookie = cookie;
            taskId = HiddenTaskApi.getTaskId(task);
            token = HiddenTaskApi.getTaskToken(task);
        }

        boolean close(final Object service) throws ReflectiveOperationException {
            final Object task = find(service, -1, cookie);
            if (task == null) { return true; }
            if (HiddenTaskApi.getTaskId(task) != taskId
                    || !HiddenTaskApi.isEmptyTask(task)) {
                throw new IllegalStateException("structural root has unexpected children: " + taskId);
            }
            final Object children = organizer.getClass().getMethod("getChildTasks",
                    FrameworkRuntime.current().windowing().tokenClass(), int[].class)
                    .invoke(organizer, token, null);
            if (!(children instanceof java.util.List<?> list) || !list.isEmpty()) {
                throw new IllegalStateException("structural root is not empty: " + taskId);
            }
            return (Boolean) organizer.getClass().getMethod("deleteRootTask",
                    FrameworkRuntime.current().windowing().tokenClass()).invoke(organizer, token);
        }
    }

    static Root create(final Object service, final int displayId) throws ReflectiveOperationException {
        final Object organizer = Class.forName("android.window.TaskOrganizer")
                .getConstructor().newInstance();
        final IBinder cookie = new Binder();
        // Do not registerOrganizer(): the system continues to own all task surfaces.
        organizer.getClass().getMethod("createRootTask", int.class, int.class, IBinder.class)
                .invoke(organizer, displayId, 1, cookie);
        // The Binder call commits creation synchronously; no settling wait is needed.
        final Object task = find(service, displayId, cookie);
        if (task == null) { throw new IllegalStateException("structural root was not created"); }
        return new Root(organizer, cookie, task);
    }

    private static Object find(Object service, int displayId, IBinder cookie)
            throws ReflectiveOperationException {
        for (final Object task : HiddenTaskApi.getRootTaskInfos(service, displayId)) {
            if (HiddenTaskApi.hasTaskLaunchCookie(task, cookie)) { return task; }
        }
        return null;
    }
}
