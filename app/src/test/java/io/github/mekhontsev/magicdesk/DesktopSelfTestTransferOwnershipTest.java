package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestTransferOwnershipTest {
    @Test
    public void nativeTransferAndDesktopAdmissionHaveSeparateAssertions() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String FIXTURE_CLASS = "magicdesk.Fixture";
                static final List<String> calls = new ArrayList<>();
                static boolean managed, attachSucceeds = true, focusSucceeds = true, ownershipKnown = true;
                static final Rect bounds = new Rect();
                static class Rect {}
                static class DesktopTaskDensity { static final int UNCHANGED = -1; }
                static class TaskRepository {
                    static class TaskEntry {
                        int taskId = 41, displayId = 7;
                        String componentName = "magicdesk/.Fixture";
                    }
                    static class Snapshot {
                        boolean available = true;
                        String error = "task snapshot unavailable";
                        List<TaskEntry> tasks = List.of(new TaskEntry());
                    }
                    static Snapshot snapshot = new Snapshot();
                    static Snapshot loadNow(int display) { return snapshot; }
                }
                static class DesktopSelfTestTasks {
                    static boolean hasClass(String component, String name) {
                        return component.equals("magicdesk/.Fixture") && name.equals(FIXTURE_CLASS);
                    }
                }
                static class ApplicationTaskPlacement {
                    static boolean isManaged(TaskRepository.TaskEntry task) throws IOException {
                        calls.add("observe");
                        if (!ownershipKnown) { throw new IOException("ownership unavailable"); }
                        return managed;
                    }
                }
                static class MagicDeskRuntime {
                    static boolean attachWindowedTask(int display, int task, Rect b, int dpi) {
                        check(display == 7 && task == 41 && b == bounds
                                && dpi == DesktopTaskDensity.UNCHANGED, "admission changed fixture target");
                        calls.add("admit");
                        if (attachSucceeds) { managed = true; }
                        return attachSucceeds;
                    }
                }
                static class DesktopSelfTestInputSuite {
                    static void focusTaskThroughDesktop(int display, int task) throws IOException {
                        check(managed && display == 7 && task == 41, "focused an independent task");
                        calls.add("focus");
                        if (!focusSucceeds) { throw new IOException("focus failed"); }
                    }
                }
                interface Check { void run() throws IOException; }
                static void fails(Check action) throws IOException {
                    try { action.run(); throw new AssertionError("failure accepted"); }
                    catch (IOException expected) { }
                }
                public static void verify() throws Exception {
                    check(verifyFixtureOwnership(7, 41, false).endsWith("independent"),
                            "raw transfer should remain independent");
                    check(calls.equals(List.of("observe")), "observation claimed the task");
                    fails(() -> verifyFixtureOwnership(7, 41, true));
                    calls.clear();
                    check(admitTransferredFixture(7, 41, bounds).endsWith("desktop"), "admission missing");
                    check(calls.equals(List.of("admit", "focus", "observe")), "admission/focus order");
                    fails(() -> verifyFixtureOwnership(7, 41, false));
                    managed = false; attachSucceeds = false; calls.clear();
                    fails(() -> admitTransferredFixture(7, 41, bounds));
                    check(calls.equals(List.of("admit")), "failed admission proceeded to focus");
                    attachSucceeds = true; focusSucceeds = false;
                    fails(() -> admitTransferredFixture(7, 41, bounds));
                    ownershipKnown = false;
                    fails(() -> verifyFixtureOwnership(7, 41, true));
                    ownershipKnown = true;
                    TaskRepository.snapshot.available = false;
                    fails(() -> verifyFixtureOwnership(7, 41, false));
                    TaskRepository.snapshot.available = true;
                    fails(() -> verifyFixtureOwnership(0, 41, false));
                    fails(() -> verifyFixtureOwnership(7, 42, false));
                    TaskRepository.snapshot.tasks.get(0).componentName = "another/Activity";
                    fails(() -> verifyFixtureOwnership(7, 41, false));
                    TaskRepository.snapshot.tasks = List.of();
                    fails(() -> verifyFixtureOwnership(7, 41, false));
                }
                """ + RuntimeSourceFixture.methods("DesktopSelfTestWindowSuite",
                        "verifyFixtureOwnership", "admitTransferredFixture"));
    }
}
