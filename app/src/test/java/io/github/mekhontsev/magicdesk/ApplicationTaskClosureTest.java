package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ApplicationTaskClosureTest {
    @Test public void repositoryClosureDoesNotRequeueInsideAnOwnershipDecision() throws Exception {
        RuntimeSourceFixture.verify("""
                record TaskEntry(int taskId, boolean controllable) { }
                record ActionResult(boolean success, String message) { }
                record CommandResult(boolean success, String output) { }
                interface ActionCallback { void onComplete(ActionResult result); }
                static class TaskCommandQueue {
                    static final List<Runnable> pending = new ArrayList<>();
                    static boolean worker;
                    interface Operation<T> { T run(); }
                    static <T> T call(Operation<T> operation) {
                        check(worker, "fixture expects the existing queue owner");
                        return operation.run();
                    }
                    static void execute(Runnable operation) { pending.add(operation); }
                    static void drain() {
                        worker = true;
                        try { while (!pending.isEmpty()) pending.remove(0).run(); }
                        finally { worker = false; }
                    }
                }
                static boolean isUsableTask(TaskEntry task) { return task != null && task.taskId >= 0; }
                static class DesktopManagedTaskPolicy {
                    static boolean isControllableApplicationTask(TaskEntry task) { return task.controllable; }
                }
                static final List<String> events = new ArrayList<>();
                static boolean fail;
                static String createTaskControlCommand(String action, int id) { return action + " " + id; }
                static CommandResult runCommand(String command) {
                    check(TaskCommandQueue.worker, "close escaped queue");
                    events.add(command); return new CommandResult(!fail, " receipt ");
                }
                public static void verify() {
                    var task = new TaskEntry(7, true);
                    List<ActionResult> results = new ArrayList<>();
                    closeTask(task, results::add);
                    check(events.isEmpty() && results.isEmpty(), "async wrapper ran inline");
                    TaskCommandQueue.drain();
                    check(results.size() == 1 && results.get(0).success
                            && results.get(0).message.equals("receipt"), "close receipt lost");
                    events.clear();
                    TaskCommandQueue.execute(() -> {
                        events.add("check-owner");
                        check(closeTaskNow(task).success, "inline close failed");
                        events.add("decision-finished");
                    });
                    TaskCommandQueue.execute(() -> events.add("next-command"));
                    TaskCommandQueue.drain();
                    check(events.equals(List.of("check-owner", "remove 7", "decision-finished", "next-command")),
                            "command interleaved between ownership and close");
                    events.clear();
                    closeTask(null, results::add);
                    closeTask(new TaskEntry(8, false), results::add);
                    TaskCommandQueue.drain();
                    check(events.isEmpty() && !results.get(1).success && !results.get(2).success,
                            "invalid or system task reached shell");
                    fail = true; closeTask(task, results::add); TaskCommandQueue.drain();
                    check(!results.get(3).success, "shell rejection became success");
                }
                """ + RuntimeSourceFixture.methods("TaskRepository", "closeTask", "closeTaskNow"));
    }

    @Test public void independentClosureNeverEntersDesktopFocusAndUnknownOwnershipCannotFallBack() throws Exception {
        RuntimeSourceFixture.verify("""
                static boolean managed, unknown, stale, transitioning;
                static int ordinaryCloses, managedCloses;
                static class BuiltInWindowRegistry {
                    static boolean hosted;
                    static int requests;
                    static boolean requestClose(TaskRepository.TaskEntry task, boolean force, TaskRepository.ActionCallback callback) {
                        check(!force, "ordinary close must not force");
                        if (!hosted) return false;
                        requests++; callback.onComplete(new TaskRepository.ActionResult(true, "close requested")); return true;
                    }
                }
                static class TaskRepository {
                    static class TaskEntry { int displayId = 7; }
                    record ActionResult(boolean success, String message) { }
                    interface ActionCallback { void onComplete(ActionResult result); }
                    static ActionResult closeTaskNow(TaskEntry task) {
                        ordinaryCloses++; return new ActionResult(true, "ordinary");
                    }
                }
                static class ApplicationTaskPlacement {
                    static TaskRepository.TaskEntry requireLive(TaskRepository.TaskEntry task) throws IOException {
                        if (stale) throw new IOException("stale task"); return task;
                    }
                    static boolean isManaged(TaskRepository.TaskEntry task) throws IOException {
                        if (unknown) throw new IOException("ownership unknown"); return managed;
                    }
                }
                static class DesktopOperations {
                    static boolean isSessionTransitionInProgress() { return transitioning; }
                }
                static class TaskCommandQueue { static void execute(Runnable action) { action.run(); } }
                static class DesktopTaskRuntime {
                    boolean accepts = true;
                    boolean closeTask(TaskRepository.TaskEntry task, TaskRepository.ActionCallback callback) {
                        if (!accepts) return false;
                        managedCloses++; callback.onComplete(new TaskRepository.ActionResult(true, "managed")); return true;
                    }
                }
                static DesktopTaskRuntime owner;
                static DesktopTaskRuntime desktopTasks(int display) { return owner; }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                public static void verify() {
                    var task = new TaskRepository.TaskEntry();
                    List<TaskRepository.ActionResult> results = new ArrayList<>();
                    closeTask(task, results::add);
                    owner = new DesktopTaskRuntime();
                    closeTask(task, results::add);
                    check(ordinaryCloses == 2 && managedCloses == 0, "independent task entered Desktop focus");
                    managed = true;
                    closeTask(task, results::add);
                    check(managedCloses == 1 && ordinaryCloses == 2, "managed task bypassed plane owner");
                    owner.accepts = false; closeTask(task, results::add);
                    owner = null; closeTask(task, results::add);
                    managed = false; unknown = true; closeTask(task, results::add);
                    unknown = false; stale = true; closeTask(task, results::add);
                    stale = false; transitioning = true; closeTask(task, results::add);
                    transitioning = false; closeTask(null, results::add);
                    check(managedCloses == 1 && ordinaryCloses == 2, "rejected ownership fell back to raw closure");
                    check(results.size() == 9 && results.subList(0, 3).stream().allMatch(r -> r.success)
                            && results.subList(3, 9).stream().noneMatch(r -> r.success), "wrong close receipts");
                    BuiltInWindowRegistry.hosted = true;
                    managed = true; unknown = true;
                    closeTask(task, results::add);
                    check(BuiltInWindowRegistry.requests == 1 && results.get(9).success,
                            "hosted close remains a request even without a Desktop owner");
                    check(managedCloses == 1 && ordinaryCloses == 2,
                            "hosted request removed the Android task or prepared focus");
                    stale = true; closeTask(task, results::add);
                    check(BuiltInWindowRegistry.requests == 1 && !results.get(10).success,
                            "stale identity reached a host");
                }
                """ + RuntimeSourceFixture.methods("MagicDeskRuntime", "closeTask"));
    }
}
