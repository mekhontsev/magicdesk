package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplaySwitchOperationTest {
    @Test public void waitsForImageBeforeInputAndAcquiresPreviouslyUnselectedInput() throws Exception {
        verify("""
                DisplayPresentations.hold = true;
                var operation = operation(2);
                operation.start(); Handler.drain();
                check(results.isEmpty() && MagicDeskRuntime.display == -1, "input preceded surface readiness");
                DisplayPresentations.complete(); Handler.drain();
                check(results.size() == 1 && results.get(0) == null, "switch failed");
                check(MagicDeskRuntime.display == 2 && DisplayPresentations.current.source.id == 2,
                        "image and input disagree");
                check(DisplayPresentations.current.inputRequest != null, "detach cannot cancel acquired input");
                """);
    }

    @Test public void routingFailureRestoresImageAndInput() throws Exception {
        verify("""
                DisplayPresentations.current = new DisplayPresentations.Session(display(1));
                MagicDeskRuntime.display = 1;
                MagicDeskRuntime.fail = 2;
                operation(2).start(); Handler.drain();
                check(results.size() == 1 && results.get(0) != null, "failure became success");
                check(DisplayPresentations.current.source.id == 1 && MagicDeskRuntime.display == 1,
                        "rollback left image/input on different displays");
                """);
    }

    @Test public void failedFirstViewerIsClosedAndOriginalInputRestored() throws Exception {
        verify("""
                MagicDeskRuntime.display = 0;
                MagicDeskRuntime.fail = 2;
                operation(2).start(); Handler.drain();
                check(results.size() == 1 && results.get(0) != null, "failure became success");
                check(DisplayPresentations.current == null && MagicDeskRuntime.display == 0, "native output not restored");
                """);
    }

    @Test public void newerInputSelectionWinsDuringBindingAndRollback() throws Exception {
        verify("""
                DisplayPresentations.current = new DisplayPresentations.Session(display(1));
                MagicDeskRuntime.display = 1;
                DisplayPresentations.hold = true;
                operation(2).start(); Handler.drain();
                MagicDeskRuntime.selectInputDisplay(3, -1, result -> {}); Handler.drain();
                DisplayPresentations.complete(); Handler.drain();
                check(results.size() == 1 && results.get(0) != null, "superseded switch became success");
                check(DisplayPresentations.current.source.id == 1, "old image not restored");
                check(MagicDeskRuntime.display == 3, "rollback stole newer input selection");
                """);
    }

    @Test public void nativeOutputClosesViewerButNotItsSourceDisplay() throws Exception {
        verify("""
                DisplayPresentations.current = new DisplayPresentations.Session(display(1));
                MagicDeskRuntime.display = 1;
                operation(0).start(); Handler.drain();
                check(results.size() == 1 && results.get(0) == null, "native switch failed");
                check(DisplayPresentations.current == null && MagicDeskRuntime.display == 0, "native output not selected");
                """);
    }

    @Test public void nativeOutputInputFailureRestoresViewer() throws Exception {
        verify("""
                DisplayPresentations.current = new DisplayPresentations.Session(display(1));
                MagicDeskRuntime.display = 1;
                MagicDeskRuntime.fail = 0;
                operation(0).start(); Handler.drain();
                check(results.size() == 1 && results.get(0) != null, "failure became success");
                check(DisplayPresentations.current.source.id == 1 && MagicDeskRuntime.display == 1,
                        "native switch did not roll back");
                """);
    }

    @Test public void vanishedIdentityFailsBeforeChangingInputOrImage() throws Exception {
        verify("""
                DesktopDisplayCatalog.missing = true;
                MagicDeskRuntime.display = 1;
                operation(2).start(); Handler.drain();
                check(results.size() == 1 && results.get(0) != null, "vanished display accepted");
                check(MagicDeskRuntime.display == 1 && DisplayPresentations.current == null, "validation changed state");
                """);
    }

    @Test public void touchpadFailureCompletesAndRestoresPreviousSelection() throws Exception {
        verify("""
                DisplayPresentations.current = new DisplayPresentations.Session(display(1));
                MagicDeskRuntime.display = 1;
                PhoneTouchpadController.requested = true;
                PhoneTouchpadController.fail = 2;
                new DisplaySwitchOperation(new Context(), display(9), display(2), results::add).start();
                Handler.drain();
                check(results.size() == 1 && results.get(0) != null, "touchpad failure stranded operation");
                check(DisplayPresentations.current.source.id == 1 && MagicDeskRuntime.display == 1,
                        "touchpad failure did not restore image/input");
                check(PhoneTouchpadController.opened == 1, "previous touchpad not restored");
                """);
    }

    @Test public void newerBindingToSameSourceSurvivesRollback() throws Exception {
        verify("""
                DisplayPresentations.current = new DisplayPresentations.Session(display(1));
                MagicDeskRuntime.display = 1;
                MagicDeskRuntime.fail = 2;
                MagicDeskRuntime.onFailure = () -> DisplayPresentations.current.bindingGeneration++;
                operation(2).start(); Handler.drain();
                check(results.size() == 1 && results.get(0) != null, "failure became success");
                check(DisplayPresentations.current.source.id == 2, "rollback overwrote newer binding");
                check(MagicDeskRuntime.display == 1, "owned input was not restored");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class Context { Context getApplicationContext() { return this; } }
                static class Looper { static Object getMainLooper() { return null; } }
                static class Handler {
                    static final Queue<Runnable> queue = new ArrayDeque<>();
                    Handler(Object looper) {}
                    void post(Runnable action) { queue.add(action); }
                    static void drain() {
                        int count = 0;
                        while (!queue.isEmpty()) { check(++count < 100, "callback loop"); queue.remove().run(); }
                    }
                }
                static class TaskCommandQueue { static void execute(Runnable action) { action.run(); } }
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                }
                static DesktopDisplayInfo display(int id) { return new DesktopDisplayInfo(id); }
                static class DesktopDisplayCatalog {
                    static boolean missing;
                    static void require(int id, String uniqueId) {
                        if (missing) throw new IllegalStateException("display is no longer available");
                    }
                }
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class TaskRepository {
                    record ActionResult(boolean success, String message) {}
                    interface ActionCallback { void onComplete(ActionResult result); }
                }
                static class MagicDeskRuntime {
                    static int display = -1, fail = -99;
                    static Runnable onFailure;
                    static final DisplayInputRequests requests = new DisplayInputRequests();
                    static int inputDisplayId() { return display; }
                    static long inputSelectionVersion() { return requests.version(); }
                    static DisplayInputRequests.Request selectInputDisplay(int id, long version, TaskRepository.ActionCallback cb) {
                        var request = requests.begin(id, version);
                        Handler.queue.add(() -> {
                            if (request == null || !request.isCurrent()) {
                                cb.onComplete(new TaskRepository.ActionResult(false, "superseded")); return;
                            }
                            display = id;
                            if (id == fail && onFailure != null) onFailure.run();
                            cb.onComplete(new TaskRepository.ActionResult(id != fail, "routing failure"));
                        });
                        return request;
                    }
                }
                static class PhoneTouchpadController {
                    static boolean requested;
                    static int fail = -99, opened = -1;
                    static boolean shouldRemainVisible(int id) { return requested; }
                    static void open(int id) {
                        if (id == fail) throw new IllegalStateException("touchpad launch failed");
                        opened = id;
                    }
                }
                static class DesktopAutomationEventJournal {
                    static void record(String a, String b, boolean ok, String detail) {}
                }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                static class DisplayPresentations {
                    static Session current;
                    static boolean hold;
                    static BuiltInWindowLauncher.Callback pending;
                    static class Session {
                        DesktopDisplayInfo source;
                        long bindingGeneration;
                        boolean ready;
                        DisplayInputRequests.Request inputRequest;
                        Session(DesktopDisplayInfo source) { this.source = source; ready = true; }
                    }
                    static Session forOutput(int id) { return current; }
                    static boolean canSwitchOutput(DesktopDisplayInfo source, DesktopDisplayInfo output) { return true; }
                    static void attachForSwitch(Context context, DesktopDisplayInfo source, DesktopDisplayInfo output,
                            BuiltInWindowLauncher.Callback cb) {
                        if (current == null) current = new Session(source);
                        current.source = source; current.bindingGeneration++; current.ready = !hold;
                        if (hold) { pending = cb; hold = false; }
                        else cb.onComplete(null);
                    }
                    static void complete() { current.ready = true; pending.onComplete(null); }
                    static void detach(Session session, BuiltInWindowLauncher.Callback cb) { current = null; cb.onComplete(null); }
                }
                static final List<Throwable> results = new ArrayList<>();
                static DisplaySwitchOperation operation(int id) {
                    return new DisplaySwitchOperation(new Context(), display(0), display(id), results::add);
                }
                public static void verify() {
                """ + scenario + "\n}\nstatic "
                + RuntimeSourceFixture.nestedClass("DisplaySwitchOperation", "DisplaySwitchOperation"),
                "DisplayInputRequests");
    }
}
