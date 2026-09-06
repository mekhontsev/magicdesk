package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FullscreenReservationRegressionTest {
    @Test
    public void failedLaunchDoesNotDeleteReturnedExistingTask() throws Exception {
        verifyCleanup("""
                f.mPlanes.put(42, plane);
                HiddenTaskApi.children.add(42);
                f.cleanupFailedLaunch(f, 4, 42, plane);
                check(HiddenTaskApi.removed.isEmpty(), "outer cleanup deleted returned task without provenance");
                check(f.mPlanes.get(42) == plane, "live plane ownership was lost");
                check(f.parked == 0, "occupied plane was parked");
                """);
    }

    @Test
    public void failedStarterDoesNotDeleteUnknownPlaneChild() throws Exception {
        verifyCleanup("""
                HiddenTaskApi.children.add(42);
                f.cleanupFailedLaunch(f, 4, -1, plane);
                check(HiddenTaskApi.removed.isEmpty(), "outer cleanup deleted unknown task without provenance");
                check(f.parked == 0, "occupied plane was parked");
                check(f.mPlanes.get(42) == plane, "observed residency was not retained");
                """);
    }

    @Test
    public void emptyFailedLaunchSlotIsReclaimed() throws Exception {
        verifyCleanup("""
                f.cleanupFailedLaunch(f, 4, -1, plane);
                check(f.parked == 1, "empty slot was not reclaimed");
                check(HiddenTaskApi.removed.isEmpty(), "cleanup attempted task deletion");
                """);
    }

    @Test
    public void retiringARecordDoesNotPublishAReusableSlot() throws Exception {
        RuntimeSourceFixture.verify("""
                static class TaskDisplayAreaHandle {}
                final Map<Integer, TaskDisplayAreaHandle> mPlanes = new LinkedHashMap<>();
                final List<TaskDisplayAreaHandle> mAvailablePlanes = new ArrayList<>();
                final List<Integer> mPlaneOrder = new ArrayList<>();
                public static void verify() {
                    Fixture f = new Fixture();
                    TaskDisplayAreaHandle plane = new TaskDisplayAreaHandle();
                    f.mPlanes.put(42, plane);
                    check(f.retirePlaneRecord(42) == plane, "wrong retired plane");
                    check(f.mAvailablePlanes.isEmpty(), "record retirement published unchecked plane as free");
                }
                """ + RuntimeSourceFixture.methods("ShellFullscreenTaskPlanes", "retirePlaneRecord"));
    }

    private static void verifyCleanup(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class TaskDisplayAreaHandle { int featureId() { return 100; } }
                static class HiddenTaskApi {
                    static final List<Integer> children = new ArrayList<>();
                    static final List<Integer> removed = new ArrayList<>();
                    static void removeTask(Object service, int task) throws ReflectiveOperationException { removed.add(task); children.remove(Integer.valueOf(task)); }
                    static Object findTask(Object service, int display, int task) throws ReflectiveOperationException { return children.contains(task) ? task : null; }
                }
                static class Log { static void w(String tag, String message) {} static void w(String tag, String message, Throwable error) {} }
                static class BoundedStateAwaiter {
                    enum Reason { TASK_REMOVAL }
                    interface Query<T> { T get() throws ReflectiveOperationException; }
                    static <T> T awaitFramework(Reason r, long timeout, long interval, Query<T> query, java.util.function.Predicate<T> done) throws ReflectiveOperationException { return query.get(); }
                }
                static final String TAG = "test";
                static final long FAILED_LAUNCH_REMOVAL_TIMEOUT_MILLIS = 1000, FAILED_LAUNCH_REMOVAL_POLL_MILLIS = 25;
                final Map<Integer, TaskDisplayAreaHandle> mPlanes = new LinkedHashMap<>();
                final List<Integer> mPlaneOrder = new ArrayList<>();
                final List<TaskDisplayAreaHandle> mAvailablePlanes = new ArrayList<>();
                int parked;
                List<Integer> unexpectedPlaneChildren(Object service, int display, TaskDisplayAreaHandle plane) throws ReflectiveOperationException { return new ArrayList<>(HiddenTaskApi.children); }
                void parkPlane(Object service, TaskDisplayAreaHandle plane) throws ReflectiveOperationException { parked++; }
                void releasePlane(Object service, int task) { mPlanes.remove(task); parked++; }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    TaskDisplayAreaHandle plane = new TaskDisplayAreaHandle();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "ShellFullscreenTaskPlanes", "cleanupFailedLaunch", "retainObservedPlaneChildren"));
    }
}
