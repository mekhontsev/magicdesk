package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Runs the actual binding transaction against explicitly scheduled UI/input boundaries. */
public final class DisplayPresentationTransactionTest {
    @Test public void committedSelectionRetainsBothReturnDestinationsUntilDetach() throws Exception {
        verify("""
                Session a = session(1, 3, true);
                attached(a);
                Change change = new Change(Map.of(a, new DesktopDisplayInfo(2)));
                change.start();
                check(!LAST_OUTPUTS.containsKey("display:2"), "pending selection remembered as committed");
                attached(a);
                check(change.finished && LAST_OUTPUTS.get("display:1") == a
                        && LAST_OUTPUTS.get("display:2") == a, "selection lost previous return destination");
                detach(a, error -> check(error == null, "detach failed")); MAIN.drain();
                check(LAST_OUTPUTS.isEmpty(), "detached output retained return destinations");
                """);
    }
    @Test public void incompatibleProtectedSwapIsRejectedBeforeEitherLeaseIsReleased() throws Exception {
        verify("""
                Session a = session(1, 3, true), b = session(2, 4, true);
                a.ready = true; b.ready = true;
                a.source.protectedMode = true;
                a.output.secure = true;
                try { swap(a, b); throw new AssertionError("unsafe swap accepted"); }
                catch (IllegalArgumentException expected) { }
                check(a.source.id == 1 && b.source.id == 2, "rejected swap changed bindings");
                check(a.ready && b.ready && a.change == null && b.change == null, "rejected swap released a lease");
                b.output.secure = true;
                Change change = swap(a, b);
                attached(a); attached(b);
                check(change.finished && b.source.protectedMode, "compatible swap failed");
                """);
    }
    @Test public void hiddenPeerDoesNotBlockVisibleOutput() throws Exception {
        verify("""
                Session a = session(1, 3, true), b = session(2, 4, false);
                Change change = swap(a, b);
                attached(a);
                check(change.finished && a.ready && a.change == null, "hidden peer blocked the switch");
                check(!b.ready && b.source.id == 1 && b.change == null, "hidden peer falsely attached");
                visibilityChanged(b, true);
                attached(b);
                check(b.ready && b.source.id == 1, "hidden binding did not resume");
                """);
    }

    @Test public void peerHiddenDuringChangeReleasesAttachmentBarrier() throws Exception {
        verify("""
                Session a = session(1, 3, true), b = session(2, 4, true);
                Change change = swap(a, b);
                attached(a);
                check(!change.finished, "visible peer was not awaited");
                visibilityChanged(b, false);
                check(change.finished && a.ready && !b.ready, "hiding peer did not release barrier");
                """);
    }

    @Test public void detachCancelsHandoffBeforeServiceExecution() throws Exception {
        verify("""
                MagicDeskRuntime.display = 1;
                Session a = session(1, 3, true);
                Change change = new Change(Map.of(a, new DesktopDisplayInfo(2)));
                change.start(); MAIN.drain();
                check(MagicDeskRuntime.display == -1, "old input was not released");
                attached(a);
                check(!MAIN.queue.isEmpty(), "no queued input acquisition");
                int[] completed = {0};
                detach(a, error -> { check(error == null, "detach failed"); completed[0]++; });
                MAIN.drain();
                check(a.closed && completed[0] == 1, "detach not completed exactly once");
                check(MagicDeskRuntime.display == -1, "detached viewer reacquired input");
                """);
    }

    @Test public void detachReleasesHandoffAlreadyExecutedByService() throws Exception {
        verify("""
                MagicDeskRuntime.display = 1;
                Session a = session(1, 3, true);
                new Change(Map.of(a, new DesktopDisplayInfo(2))).start(); MAIN.drain();
                attached(a); MAIN.queue.remove().run();
                check(MagicDeskRuntime.display == 2, "handoff was not executed");
                detach(a, error -> check(error == null, "detach failed")); MAIN.drain();
                check(MagicDeskRuntime.display == -1, "applied handoff survived detach");
                """);
    }

    @Test public void laterExplicitSelectionWinsEvenForTheSameDisplay() throws Exception {
        verify("""
                MagicDeskRuntime.display = 1;
                Session a = session(1, 3, true);
                Change change = new Change(Map.of(a, new DesktopDisplayInfo(2)));
                change.start(); MAIN.drain();
                MagicDeskRuntime.selectInputDisplay(-1, result -> {});
                attached(a); MAIN.drain();
                check(change.finished && a.ready && MagicDeskRuntime.display == -1,
                        "handoff overrode later explicit release");
                """);
    }

    @Test public void detachCancelsViewerControlButtonRequest() throws Exception {
        verify("""
                Session a = session(1, 3, true); a.ready = true;
                controlInput(a, result -> {});
                detach(a, error -> check(error == null, "detach failed")); MAIN.drain();
                check(MagicDeskRuntime.display == -1, "Control button acquired input after detach");
                """);
    }

    @Test public void reopeningCanChangeFullscreenWithoutChangingBinding() throws Exception {
        verify("""
                Session a = session(1, 3, true); a.ready = true;
                setFullscreen(a, true);
                check(a.fullscreen && a.ready && a.bindingGeneration == 0, "fullscreen rebound source");
                setFullscreen(a, false);
                check(!a.fullscreen && a.ready, "windowed request ignored");
                """);
    }

    private static void verify(String assertions) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class ShellAccess { static String usefulMessage(Throwable e) { return e.toString(); } }
                static class CompatibilityDiagnostics { static void record(String a, String b, String c) {} }
                static class TaskRepository {
                    interface ActionCallback { void onComplete(ActionResult result); }
                    static class ActionResult {
                        final boolean success; final String message;
                        ActionResult(boolean success, String message) { this.success = success; this.message = message; }
                    }
                }
                static class Main {
                    final Deque<Runnable> queue = new ArrayDeque<>();
                    void post(Runnable r) { queue.add(r); }
                    void drain() { while (!queue.isEmpty()) queue.remove().run(); }
                }
                static final Main MAIN = new Main();
                static class MagicDeskRuntime {
                    static int display = -1;
                    static DisplayInputRequests requests = new DisplayInputRequests();
                    static int inputDisplayId() { return display; }
                    static long inputSelectionVersion() { return requests.version(); }
                    static void releaseSelectedInput(int id, TaskRepository.ActionCallback cb) {
                        var request = requests.beginRelease(id);
                        MAIN.post(() -> {
                            if (request != null && request.isCurrent() && display == id) display = -1;
                            cb.onComplete(new TaskRepository.ActionResult(true, "released"));
                        });
                    }
                    static DisplayInputRequests.Request selectInputDisplay(int id, TaskRepository.ActionCallback cb) {
                        return selectInputDisplay(id, -1, cb);
                    }
                    static DisplayInputRequests.Request selectInputDisplay(int id, long expected, TaskRepository.ActionCallback cb) {
                        DisplayInputRequests.Request request = requests.begin(id, expected);
                        MAIN.post(() -> {
                            boolean valid = request != null && request.isCurrent();
                            if (valid) display = id;
                            cb.onComplete(new TaskRepository.ActionResult(valid, valid ? "ready" : "cancelled"));
                        });
                        return request;
                    }
                }
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId;
                    boolean secure, protectedMode;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                    boolean protectedContent() { return protectedMode; }
                """ + RuntimeSourceFixture.methods("DesktopDisplayInfo", "requirePresentationOutput") + """
                }
                static class DisplayPresentations {
                """ + RuntimeSourceFixture.nestedClass("DisplayPresentations", "Listener")
                + RuntimeSourceFixture.nestedClass("DisplayPresentations", "Session")
                + RuntimeSourceFixture.nestedClass("DisplayPresentations", "Change")
                + RuntimeSourceFixture.topLevelMethods("DisplayPresentations", "detach", "attached", "visibilityChanged",
                        "setFullscreen", "controlInput", "rememberOutput") + """
                    static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
                    static final Map<String, Session> LAST_OUTPUTS = new LinkedHashMap<>();
                    static void notify(Session s) { if (s.listener != null) s.listener.changed(); }
                    static void complete(Session s, Throwable e) {
                        var callbacks = new ArrayList<>(s.completions); s.completions.clear();
                        callbacks.forEach(c -> c.onComplete(e));
                    }
                    static void validateGraph(Map<Session, DesktopDisplayInfo> next) {}
                    static Session session(int source, int output, boolean visible) {
                        Session s = new Session(new DesktopDisplayInfo(source), new DesktopDisplayInfo(output), false);
                        s.visible = visible;
                        s.listener = new Listener() {
                            public void changed() {}
                            public void show(BuiltInWindowLauncher.Callback cb) { cb.onComplete(null); }
                            public void detach(BuiltInWindowLauncher.Callback cb) { cb.onComplete(null); }
                        };
                        SESSIONS.put(s.id, s); return s;
                    }
                    static Change swap(Session a, Session b) {
                        Change c = new Change(Map.of(a, b.source, b, a.source)); c.start(); return c;
                    }
                    static void runAssertions() {
                """ + assertions + """
                    }
                }
                public static void verify() { DisplayPresentations.runAssertions(); }
                """, "DisplayInputRequests");
    }
}
