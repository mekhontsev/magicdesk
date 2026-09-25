package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestNativeFullscreenPeerTest {
    @Test
    public void acceptsUnchangedVisiblePeerBetweenForegroundAndHome() throws Exception {
        verify("""
                check(matches(List.of(front, peer, home)), "valid peer order rejected");
                check(matches(List.of(task(99), front, peer, home)), "chrome changed peer order");
                """);
    }

    @Test
    public void detectsPeerBehindHomeEvenWhenFrameworkSaysVisible() throws Exception {
        verify("""
                check(!matches(List.of(front, home, peer)), "covered peer accepted");
                check(!matches(List.of(peer, front, home)), "peer raised over restored task");
                """);
    }

    @Test
    public void rejectsMissingTasksAndUnknownSnapshots() throws Exception {
        verify("""
                check(!matches(List.of(front, peer)), "missing HOME accepted");
                check(!matches(List.of(front, home)), "missing peer accepted");
                check(!matches(List.of(peer, home)), "missing foreground accepted");
                TaskRepository.Snapshot unknown = new TaskRepository.Snapshot(List.of(front, peer, home));
                unknown.available = false;
                check(!freeformPeerOrderMatches(unknown, 10, 20, 30, bounds), "unknown order accepted");
                """);
    }

    @Test
    public void rejectsChangedPeerGeometryModeAndVisibility() throws Exception {
        verify("""
                peer.bounds = new Rect(2);
                check(!matches(List.of(front, peer, home)), "resized peer accepted");
                peer.bounds = bounds;
                peer.visible = false;
                check(!matches(List.of(front, peer, home)), "hidden peer accepted");
                peer.visible = true;
                peer.freeform = false;
                check(!matches(List.of(front, peer, home)), "fullscreen peer accepted");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                record Rect(int value) {}
                static final Rect bounds = new Rect(1);
                static class TaskRepository {
                    static class Snapshot {
                        boolean available = true;
                        List<TaskEntry> tasks;
                        Snapshot(List<TaskEntry> tasks) { this.tasks = tasks; }
                    }
                    static class TaskEntry {
                        int taskId;
                        boolean visible = true, freeform = true;
                        Rect bounds = Fixture.bounds;
                        boolean isFreeform() { return freeform; }
                    }
                }
                static TaskRepository.TaskEntry task(int id) {
                    var task = new TaskRepository.TaskEntry(); task.taskId = id; return task;
                }
                static boolean matches(List<TaskRepository.TaskEntry> tasks) {
                    return freeformPeerOrderMatches(new TaskRepository.Snapshot(tasks), 10, 20, 30, bounds);
                }
                public static void verify() {
                    var front = task(30); var peer = task(20); var home = task(10);
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "DesktopSelfTestWindowSuite", "freeformPeerOrderMatches"));
    }
}
