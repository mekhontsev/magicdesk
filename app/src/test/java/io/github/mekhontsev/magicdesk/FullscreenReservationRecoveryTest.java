package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FullscreenReservationRecoveryTest {
    @Test
    public void rejectedReparentCanRetirePositivelyEmptySlot() throws Exception {
        verify("""
                HiddenTaskApi.tasks.add(new Task(42, 0));
                f.discardStalePlaneRecords(f, 4);
                check(f.parked == 1 && f.mPlanes.isEmpty(), "verified empty reservation was not retired");
                check(f.mUnconfirmedPlanes.isEmpty(), "verified rejection poisons later reconciliation");
                """);
    }

    @Test
    public void goneTaskCanRetirePositivelyEmptySlot() throws Exception {
        verify("""
                f.discardStalePlaneRecords(f, 4);
                check(f.parked == 1 && f.mPlanes.isEmpty(), "gone task poisoned empty reservation");
                """);
    }

    @Test
    public void occupiedSlotRetainsActualOwner() throws Exception {
        verify("""
                HiddenTaskApi.tasks.add(new Task(42, 100));
                f.discardStalePlaneRecords(f, 4);
                check(f.parked == 0 && f.mPlanes.get(42) == plane, "occupied plane ownership was lost");
                check(f.mUnconfirmedPlanes.isEmpty(), "observed residency remains unknown");
                """);
    }

    @Test
    public void unavailableObservationDoesNotPublishFreeSlot() throws Exception {
        verify("""
                HiddenTaskApi.fail = true;
                try { f.discardStalePlaneRecords(f, 4); throw new AssertionError("failure expected"); }
                catch (ReflectiveOperationException expected) {}
                check(f.parked == 0 && f.mPlanes.get(42) == plane, "unknown observation lost owner");
                check(f.mUnconfirmedPlanes.contains(plane), "unknown observation became empty");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                record Task(int id, int feature) {}
                static class TaskDisplayAreaHandle { int featureId() { return 100; } }
                static class HiddenTaskApi {
                    static boolean fail;
                    static final List<Task> tasks = new ArrayList<>(List.of(new Task(10, 100)));
                    static List<Task> getTasks(Object service, int display) throws ReflectiveOperationException {
                        if (fail) throw new ReflectiveOperationException("unavailable"); return tasks;
                    }
                    static int getTaskId(Object task) { return ((Task) task).id; }
                    static int getTaskDisplayAreaFeatureId(Object task) { return ((Task) task).feature; }
                }
                final Map<Integer, TaskDisplayAreaHandle> mPlanes = new LinkedHashMap<>();
                final Map<TaskDisplayAreaHandle, Integer> mPlaneAnchorTaskIds = new LinkedHashMap<>();
                final Set<TaskDisplayAreaHandle> mUnconfirmedPlanes = new LinkedHashSet<>();
                final List<Integer> mPlaneOrder = new ArrayList<>();
                int parked;
                void removeTask(Object service, int id) { mPlanes.remove(id); parked++; }
                void parkPlane(Object service, TaskDisplayAreaHandle plane) { parked++; }
                public static void verify() throws Exception {
                    Fixture f = new Fixture(); TaskDisplayAreaHandle plane = new TaskDisplayAreaHandle();
                    f.mPlanes.put(42, plane); f.mPlaneAnchorTaskIds.put(plane, 10); f.mUnconfirmedPlanes.add(plane);
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "ShellFullscreenTaskPlanes", "discardStalePlaneRecords",
                "retainObservedPlaneChildren", "retirePlaneRecord"));
    }
}
