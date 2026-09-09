package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestLiveFixtureCleanupTest {
    @Test
    public void scenarioCleanupRetainsItsPrimaryFixtureAndUnrelatedApps() throws Exception {
        verify("""
                tasks.add(new TaskRepository.TaskEntry(43, 4, "fullscreen", true));
                tasks.add(new TaskRepository.TaskEntry(99, 0, "freeform", false));
                removeFixtureTasksExcept(Set.of(42));
                check(events.equals(List.of("close:43", "ack:43", "absent:43")),
                        "scenario cleanup touched retained or unrelated tasks: " + events);
                check(rawCommands.isEmpty(), "scenario cleanup bypassed production close");
                """);
    }

    @Test
    public void liveFixturesAwaitProductionCloseAndExactAbsence() throws Exception {
        verify("""
                tasks.add(new TaskRepository.TaskEntry(43, 4, "fullscreen", true));
                tasks.add(new TaskRepository.TaskEntry(99, 0, "freeform", false));
                removeFixtureTasks();
                check(events.equals(List.of("close:42", "ack:42", "absent:42", "repository:42",
                        "close:43", "ack:43", "absent:43")), "wrong live close route/order: " + events);
                check(rawCommands.isEmpty(), "live fixture used raw conversion/removal: " + rawCommands);
                """);
    }

    @Test
    public void failedCloseCannotFallBackToRawRemoval() throws Exception {
        verify("""
                closeSuccess = false;
                expectFailure("rejected");
                check(events.equals(List.of("close:42", "ack:42")), "failed close continued: " + events);
                check(rawCommands.isEmpty(), "failed close used raw fallback");
                """);
    }

    @Test
    public void missingCloseAcknowledgementCannotReportSuccess() throws Exception {
        verify("""
                deliverAcknowledgement = false;
                expectFailure("timed out");
                check(events.equals(List.of("close:42")), "unacknowledged close continued: " + events);
                check(rawCommands.isEmpty(), "unacknowledged close used raw fallback");
                """);
    }

    @Test
    public void successfulCloseStillRequiresTaskAbsence() throws Exception {
        verify("""
                taskRemains = true;
                expectFailure("remained");
                check(events.equals(List.of("close:42", "ack:42", "absent:42")), "absence not verified: " + events);
                check(rawCommands.isEmpty(), "absent check failure used raw fallback");
                """);
    }

    @Test
    public void unavailableObservationIsNotAnEmptyFixtureSet() throws Exception {
        verify("""
                snapshotAvailable = false;
                expectFailure("unavailable");
                check(events.isEmpty() && rawCommands.isEmpty(), "unavailable observation mutated tasks");
                """);
    }

    @Test
    public void shellLossAfterAcknowledgementCannotPassVerification() throws Exception {
        verify("""
                loseShellOnAcknowledgement = true;
                expectFailure("shell unavailable");
                check(events.equals(List.of("close:42", "ack:42")), "shell loss continued verification: " + events);
                check(rawCommands.isEmpty(), "shell loss used raw fallback");
                """);
    }

    @Test
    public void interruptedAcknowledgementPreservesInterruption() throws Exception {
        verify("""
                interruptAcknowledgement = true;
                try {
                    expectFailure("interrupted");
                    check(Thread.currentThread().isInterrupted(), "interruption discarded");
                    check(rawCommands.isEmpty(), "interruption used raw fallback");
                } finally { Thread.interrupted(); }
                """);
    }

    @Test
    public void postSessionRecoveryRetainsPhoneExitAndRepositoryChecks() throws Exception {
        verify("""
                DesktopRuntimeBridge.active = false;
                Set<Integer> phoneIds = new LinkedHashSet<>();
                removeFixtureTasks(phoneIds, Set.of(42));
                check(phoneIds.equals(Set.of(42)), "post-session fixture identity lost");
                check(rawCommands.equals(List.of("fullscreen:42")), "post-session recovery bypassed production close: " + rawCommands);
                check(events.equals(List.of("mode:42", "repository:42", "close:42", "ack:42", "absent:42", "repository:42")), "repository checks changed: " + events);
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final List<String> events = new ArrayList<>(), rawCommands = new ArrayList<>();
                static final List<TaskRepository.TaskEntry> tasks = new ArrayList<>();
                static boolean snapshotAvailable = true, closeSuccess = true, deliverAcknowledgement = true;
                static boolean taskRemains, loseShellOnAcknowledgement, interruptAcknowledgement;
                static Runnable pendingAcknowledgement;
                static final long STEP_TIMEOUT_MILLIS = 1000;
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static class DesktopRuntimeBridge {
                    static boolean active = true;
                    static boolean isLocalDesktopActiveOrStarting() { return active; }
                }
                static class TaskRepository {
                    static class TaskEntry {
                        final int taskId, displayId; final String windowingMode; final boolean fixture;
                        TaskEntry(int id, int display, String mode, boolean owned) {
                            taskId = id; displayId = display; windowingMode = mode; fixture = owned;
                        }
                    }
                    static class Snapshot {
                        final List<TaskEntry> tasks; final boolean available; final String error = "snapshot unavailable";
                        Snapshot() { tasks = List.copyOf(Fixture.tasks); available = snapshotAvailable; }
                    }
                    static Snapshot loadAllNow() { return new Snapshot(); }
                    static class ActionResult {
                        final boolean success; final String message;
                        ActionResult(boolean ok, String text) { success = ok; message = text; }
                    }
                    interface ActionCallback { void onComplete(ActionResult result); }
                    static String createClientPreservingFullscreenTransitionCommand(int display, int task) { return "fullscreen:" + task; }
                }
                static class DesktopSelfTestComponents {
                    static boolean isFixtureTask(TaskRepository.TaskEntry task) { return task.fixture; }
                }
                static class MagicDeskRuntime {
                    static void closeTask(TaskRepository.TaskEntry task, TaskRepository.ActionCallback callback) {
                        events.add("close:" + task.taskId);
                        pendingAcknowledgement = () -> {
                            events.add("ack:" + task.taskId);
                            if (loseShellOnAcknowledgement) ShellAccess.ready = false;
                            callback.onComplete(new TaskRepository.ActionResult(closeSuccess, closeSuccess ? "" : "rejected"));
                        };
                    }
                }
                static class CountDownLatch {
                    int count; CountDownLatch(int value) { count = value; }
                    void countDown() { count--; }
                    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
                        if (interruptAcknowledgement) throw new InterruptedException();
                        if (deliverAcknowledgement && pendingAcknowledgement != null) {
                            Runnable callback = pendingAcknowledgement; pendingAcknowledgement = null; callback.run();
                        }
                        return count == 0;
                    }
                }
                static class DesktopSelfTestTasks {
                    static void waitForTaskAbsent(int task) throws IOException {
                        events.add("absent:" + task);
                        if (taskRemains) throw new IOException("task remained after close");
                    }
                }
                static class ShellAccess {
                    static boolean ready = true;
                    static boolean isReady() { return ready; }
                    static String run(String command) throws IOException { rawCommands.add(command); return ""; }
                }
                static class DesktopCompatibilityPolicy {
                    enum Option { PHONE_TASK_RECOVERY }
                    boolean enabled(Option option) { return true; }
                }
                static class DesktopCompatibilitySettings {
                    static DesktopCompatibilityPolicy current() { return new DesktopCompatibilityPolicy(); }
                }
                static class TaskStackParser { static class Entry { int taskId, displayId; String windowingMode; } }
                interface TaskPredicate { boolean test(TaskStackParser.Entry entry); }
                static void waitForTask(int display, String name, TaskPredicate predicate) {
                    TaskStackParser.Entry entry = new TaskStackParser.Entry(); entry.taskId = 42; entry.windowingMode = "fullscreen";
                    check(predicate.test(entry), "phone recovery predicate changed"); events.add("mode:" + entry.taskId);
                }
                static String fixtureClass(TaskRepository.TaskEntry task) { return "fixture"; }
                static void waitForTaskAbsentFromDesktopRepository(int display, int task) {
                    check(display == 0, "repository check escaped phone display"); events.add("repository:" + task);
                }
                static void expectFailure(String reason) throws IOException {
                    try { removeFixtureTasks(); throw new AssertionError("live cleanup reported success: " + events + rawCommands); }
                    catch (IOException error) { check(error.getMessage().contains(reason), "wrong failure: " + error); }
                }
                public static void verify() throws IOException {
                    tasks.add(new TaskRepository.TaskEntry(42, 0, "freeform", true));
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("DesktopSelfTestCleanup",
                "removeFixtureTasks", "removeFixtureTasksExcept", "closeFixture", "captureFixtureTaskIds", "requiresPhoneDesktopExitBeforeRemoval", "requireShell"));
    }
}
