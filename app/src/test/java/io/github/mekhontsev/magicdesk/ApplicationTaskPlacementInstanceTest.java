package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ApplicationTaskPlacementInstanceTest {
    @Test
    public void firstInstanceIsAllowedButExistingTaskOnAnyDisplayIsRejected() throws Exception {
        verify("""
                ApplicationTaskPlacement.rejectExistingInstance(identity);
                check(TaskRepository.reads == 1, "first launch missed task check");
                for (int display : new int[] {0, 53, 99}) {
                    TaskRepository.current = new TaskRepository.Snapshot(List.of(task(10, "app", "Main", display)));
                    reject("already open");
                }
                check(TaskRepository.reads == 4, "queried repeatedly or missed other display");
                """);
    }

    @Test
    public void otherProfileAndUnrelatedActivitiesDoNotBlockFirstInstance() throws Exception {
        verify("""
                TaskRepository.current = new TaskRepository.Snapshot(List.of(
                        task(0, "app", "Main", 53), task(10, "other", "Main", 53), task(10, "app", "Other", 53)));
                ApplicationTaskPlacement.rejectExistingInstance(identity);
                check(TaskRepository.reads == 1, "incorrect profile or activity boundary");
                """);
    }

    @Test
    public void unknownTaskStateMustNotBeTreatedAsAnEmptyList() throws Exception {
        verify("""
                TaskRepository.current.available = false;
                TaskRepository.current.error = "observer unavailable";
                reject("observer unavailable");
                check(TaskRepository.reads == 1, "failed task query retried");
                """);
    }

    @Test
    public void missingShellDoesNotStartAServiceOrSilentlyAllowReuse() throws Exception {
        verify("""
                ShellAccess.ready = false;
                reject("Shell access is required");
                check(TaskRepository.reads == 0, "new-instance check tried to acquire shell access");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final LaunchActivityIdentity identity = new LaunchActivityIdentity(10, "app", "Main");
                record LaunchActivityIdentity(int user, String pkg, String activity) {
                    boolean matchesTask(TaskRepository.TaskEntry task) {
                        return task.userId == user && pkg.equals(task.packageName) && activity.equals(task.component);
                    }
                }
                static class ShellAccess { static boolean ready = true; static boolean isReady() { return ready; } }
                static class TaskRepository {
                    record TaskEntry(int userId, String packageName, String component, int displayId, boolean home) {}
                    static class Snapshot {
                        final List<TaskEntry> tasks; boolean available = true; String error = "";
                        Snapshot(List<TaskEntry> tasks) { this.tasks = tasks; }
                    }
                    static Snapshot current = new Snapshot(List.of());
                    static int reads;
                    static Snapshot loadAllNow() { reads++; return current; }
                }
                static TaskRepository.TaskEntry task(int user, String pkg, String activity, int display) {
                    return new TaskRepository.TaskEntry(user, pkg, activity, display, false);
                }
                static class ApplicationTaskPlacement {
                """ + RuntimeSourceFixture.methods("ApplicationTaskPlacement", "rejectExistingInstance", "selectExisting")
                + "}\n" + """
                static void reject(String message) {
                    try {
                        ApplicationTaskPlacement.rejectExistingInstance(identity);
                        throw new AssertionError("launch was not rejected");
                    } catch (IllegalArgumentException | IllegalStateException expected) {
                        check(expected.getMessage().contains(message), "unexpected error: " + expected);
                    }
                }
                public static void verify() {
                """ + scenario + "}\n");
    }
}
