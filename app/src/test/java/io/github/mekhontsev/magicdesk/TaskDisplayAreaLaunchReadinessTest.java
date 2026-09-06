package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class TaskDisplayAreaLaunchReadinessTest {
    @Test
    public void createdCallbackWaitsForHiddenStagedTaskToBecomeQueryable() throws Exception {
        verify("""
                source.taskId = 44941;
                observations.add(List.of());
                observations.add(List.of(task(44941, "fixture")));
                check(waitForTask(service, 0, -1, "fixture", Set.of(), source) == 44941,
                        "launch lost the created task identity");
                check(reads == 2, "created callback bypassed task query readiness");
                check(awaits == 1 && source.calls == 1, "added another launch wait");
                check(!observations.get(1).get(0).visible
                        && observations.get(1).get(0).windowingMode == 1,
                        "fixture must remain hidden/fullscreen until reveal");
                """);
    }

    @Test
    public void callbackIdentityCannotFallBackToAnotherTaskInTheSamePackage() throws Exception {
        verify("""
                source.taskId = 44941;
                observations.add(List.of(task(44942, "fixture")));
                observations.add(List.of(task(44942, "fixture"), task(44941, "fixture")));
                check(waitForTask(service, 0, -1, "fixture", Set.of(), source) == 44941,
                        "package fallback selected a different task");
                check(reads == 2, "callback identity was not confirmed in typed state");
                """);
    }

    @Test
    public void missingCallbackTaskTimesOutWithoutAcceptingPackagePeer() throws Exception {
        verify("""
                source.taskId = 44941;
                observations.add(List.of(task(44942, "fixture")));
                observations.add(List.of(task(44942, "fixture")));
                try {
                    waitForTask(service, 0, -1, "fixture", Set.of(), source);
                    throw new AssertionError("unqueryable callback task reported success");
                } catch (IllegalStateException expected) {
                    check("task did not appear".equals(expected.getMessage()),
                            "unexpected launch failure: " + expected);
                }
                check(reads == 2 && awaits == 1, "appearance deadline was not retained");
                """);
    }

    @Test
    public void unavailableQueryDoesNotTurnCreatedCallbackIntoSuccess() throws Exception {
        verify("""
                source.taskId = 44941;
                queryFailure = new ReflectiveOperationException("task service unavailable");
                try {
                    waitForTask(service, 0, -1, "fixture", Set.of(), source);
                    throw new AssertionError("unknown task state reported successful launch");
                } catch (ReflectiveOperationException expected) {
                    check(expected == queryFailure, "query failure was replaced");
                }
                check(reads == 1, "unavailable query was bypassed or retried");
                """);
    }

    @Test
    public void absentCallbackRetainsExactPackageAndPrelaunchExclusions() throws Exception {
        verify("""
                observations.add(List.of(task(44940, "fixture"), task(44942, "fixture.other")));
                observations.add(List.of(task(44940, "fixture"), task(44941, "fixture")));
                check(waitForTask(service, 0, -1, "fixture", Set.of(44940), source) == 44941,
                        "fallback lost exact package identity or prelaunch exclusions");
                check(reads == 2 && source.calls == 1, "fallback sampling changed");
                """);
    }

    @Test
    public void excludedCallbackCannotReplaceFreshTaskObservation() throws Exception {
        verify("""
                source.taskId = 44940;
                observations.add(List.of(task(44940, "fixture"), task(44941, "fixture")));
                check(waitForTask(service, 0, -1, "fixture", Set.of(44940), source) == 44941,
                        "stale callback replaced a fresh task");
                check(reads == 1, "stale callback changed readiness path");
                """);
    }

    @Test
    public void explicitTaskIdentityTakesPriorityOverCallbackHint() throws Exception {
        verify("""
                source.taskId = 44942;
                observations.add(List.of(task(44942, "fixture")));
                observations.add(List.of(task(44941, "fixture")));
                check(waitForTask(service, 0, 44941, "fixture", Set.of(), source) == 44941,
                        "callback replaced explicit task identity");
                check(reads == 2, "explicit task readiness was not observed");
                """);
    }

    @Test
    public void sourceFreeLaunchStillAcceptsQueryableHiddenTask() throws Exception {
        verify("""
                observations.add(List.of(task(44941, "fixture")));
                check(waitForTask(service, 0, -1, "fixture", Set.of()) == 44941,
                        "ordinary launch did not accept its hidden task");
                check(reads == 1 && awaits == 1 && source.calls == 0,
                        "source-free launch gained a callback dependency");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final long TASK_TIMEOUT_MILLIS = 5_000L;
                static final Object service = new Object();
                static final List<List<FrameworkTaskSnapshot>> observations = new ArrayList<>();
                static int reads, awaits;
                static ReflectiveOperationException queryFailure;
                record FrameworkTaskSnapshot(int taskId, String packageName,
                        boolean visible, int windowingMode) {}
                static FrameworkTaskSnapshot task(int id, String packageName) {
                    return new FrameworkTaskSnapshot(id, packageName, false, 1);
                }
                static class TaskIdSource {
                    int taskId = -1, calls;
                    int awaitTaskId(long timeoutMillis) {
                        check(timeoutMillis == 50L, "callback budget changed");
                        calls++; return taskId;
                    }
                }
                static class FrameworkTaskSnapshotSource {
                    static List<FrameworkTaskSnapshot> readWindowState(Object requestedService,
                            int displayId, int limit) throws ReflectiveOperationException {
                        check(requestedService == service && displayId == 0 && limit == 100,
                                "appearance query changed service, scope or bound");
                        reads++;
                        if (queryFailure != null) throw queryFailure;
                        return observations.get(reads - 1);
                    }
                }
                // The production predicate runs against a finite sequence, with no wall-clock wait.
                static class BoundedStateAwaiter {
                    enum Reason { TASK_APPEARANCE }
                    interface FrameworkProbe<T> { T sample() throws ReflectiveOperationException; }
                    interface Condition<T> { boolean isSatisfied(T value); }
                    static <T> T awaitFramework(Reason reason, long timeout, long interval,
                            FrameworkProbe<T> probe, Condition<T> condition)
                            throws ReflectiveOperationException {
                        check(reason == Reason.TASK_APPEARANCE && timeout == 5_000L && interval == 50L,
                                "appearance reason, deadline or interval changed");
                        awaits++;
                        T value;
                        do {
                            value = probe.sample();
                            if (condition.isSatisfied(value)) return value;
                        } while (reads < observations.size());
                        return value;
                    }
                }
                public static void verify() throws Exception {
                    TaskIdSource source = new TaskIdSource();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "TaskDisplayAreaLaunchCommand", "waitForTask", "findMatchingTask", "isFreshTaskId"));
    }
}
