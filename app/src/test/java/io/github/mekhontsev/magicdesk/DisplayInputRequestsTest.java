package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DisplayInputRequestsTest {
    @Test public void cancelledOrSupersededCommandsCannotExecute() {
        DisplayInputRequests requests = new DisplayInputRequests();
        var a = requests.begin(1, -1);
        a.cancel();
        assertFalse(a.isCurrent());
        var b = requests.begin(1, -1);
        var c = requests.begin(1, -1);
        assertFalse(b.isCurrent());
        assertTrue(c.isCurrent());
        assertNull(requests.begin(2, b.version));
        assertTrue(c.isCurrent());
    }

    @Test public void desktopLifecycleInvalidatesPendingSelection() {
        DisplayInputRequests requests = new DisplayInputRequests();
        var a = requests.begin(1, -1);
        requests.release(2);
        assertTrue(a.isCurrent());
        requests.release(1);
        assertFalse(a.isCurrent());
        var b = requests.begin(2, -1);
        requests.invalidate();
        assertFalse(b.isCurrent());
    }

    @Test public void runtimeChecksCancellationAtExecutionNotJustSubmission() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                final DisplayInputRequests mInputRequests = new DisplayInputRequests();
                final Deque<Runnable> queue = new ArrayDeque<>();
                final Input mDisplayInput = new Input();
                final Displays mDisplayCoordinator = new Displays();
                static class Input {
                    int selected = -1;
                    void selectDisplay(int id, TaskRepository.ActionCallback cb) {
                        selected = id; cb.onComplete(new TaskRepository.ActionResult(true, "ready"));
                    }
                }
                static class Displays { boolean hasDisplay(int id) { return true; } }
                static class ShellAccess { static boolean isReady() { return true; } }
                static class DesktopOperations { static boolean isSessionTransitionInProgress() { return false; } }
                static class TaskRepository {
                    interface ActionCallback { void onComplete(ActionResult result); }
                    record ActionResult(boolean success, String message) {}
                }
                boolean postIfAlive(Runnable r) { queue.add(r); return true; }
                void ensureInputRuntime() {}
                public static void verify() {
                    Fixture f = new Fixture();
                    List<TaskRepository.ActionResult> results = new ArrayList<>();
                    DisplayInputRequests.Request request = f.selectInputDisplay(8, -1, results::add);
                    request.cancel(); f.queue.remove().run();
                    check(f.mDisplayInput.selected == -1 && results.size() == 1 && !results.get(0).success(),
                            "runtime executed cancelled selection");
                    f.selectInputDisplay(8, -1, results::add);
                    f.selectInputDisplay(9, -1, results::add);
                    f.queue.remove().run(); f.queue.remove().run();
                    check(f.mDisplayInput.selected == 9 && results.size() == 3, "supersession was not fenced");
                }
                """ + RuntimeSourceFixture.methods("MagicDeskRuntimeService", "selectInputDisplay"),
                "DisplayInputRequests");
    }
}
