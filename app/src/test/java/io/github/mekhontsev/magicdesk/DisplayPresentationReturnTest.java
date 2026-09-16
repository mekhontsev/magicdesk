package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayPresentationReturnTest {
    @Test public void returningWaitsForAttachmentAndPreservesTheBinding() throws Exception {
        RuntimeSourceFixture.verify("""
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = "display:" + id; }
                    void requirePresentationOutput(DesktopDisplayInfo output) { }
                }
                static class Listener {
                    int shows;
                    BuiltInWindowLauncher.Callback pending;
                    void show(BuiltInWindowLauncher.Callback callback) { shows++; pending = callback; }
                }
                static class Session {
                    DesktopDisplayInfo source = new DesktopDisplayInfo(2);
                    DesktopDisplayInfo output = new DesktopDisplayInfo(0);
                    Listener listener = new Listener();
                    boolean closed, ready, outputAttachment = true;
                    String error = "";
                    long bindingGeneration;
                    Object change;
                    List<BuiltInWindowLauncher.Callback> completions = new ArrayList<>();
                }
                static class Main { void post(Runnable runnable) { runnable.run(); } }
                static class TaskCommandQueue { static void execute(Runnable runnable) { runnable.run(); } }
                static final Main MAIN = new Main();
                static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
                static final Map<String, Session> LAST_OUTPUTS = new LinkedHashMap<>();
                static Session current;
                static DesktopDisplayInfo requireSource(int id, String uniqueId) { return new DesktopDisplayInfo(id); }
                static Session forSource(int id) {
                    return current != null && current.source.id == id ? current : null;
                }
                static void attached(Session s) {
                    s.ready = true;
                    var callbacks = new ArrayList<>(s.completions);
                    s.completions.clear();
                    callbacks.forEach(c -> c.onComplete(null));
                }
                static void select(Session s, int id, String uniqueId, boolean followInput, BuiltInWindowLauncher.Callback callback) {
                    check(followInput, "ordinary return lost conditional input handoff");
                    if (!s.source.uniqueId.equals(uniqueId)) {
                        rememberOutput(s);
                        s.source = new DesktopDisplayInfo(id);
                        s.bindingGeneration++;
                        s.ready = false;
                        rememberOutput(s);
                    }
                    if (!s.error.isEmpty()) callback.onComplete(new IllegalStateException(s.error));
                    else callback.onComplete(null);
                }
                public static void verify() {
                    List<Throwable> results = new ArrayList<>();
                    showForSource(0, results::add);
                    check(results.size() == 1 && results.remove(0) == null, "ordinary output did not proceed");
                    current = new Session();
                    showForSource(2, results::add);
                    check(current.listener.shows == 1 && results.isEmpty(), "show request acknowledged as ready");
                    current.listener.pending.onComplete(null);
                    check(results.isEmpty() && current.completions.size() == 1, "hidden Surface was not awaited");
                    attached(current);
                    check(results.size() == 1 && results.remove(0) == null, "attachment not delivered");
                    showForSource(2, results::add);
                    current.listener.pending.onComplete(null);
                    check(results.size() == 1 && results.remove(0) == null, "ready output blocked");
                    rememberOutput(current);
                    select(current, 3, "display:3", true, error -> {});
                    showForSource(2, results::add);
                    current.listener.pending.onComplete(null);
                    check(current.source.id == 2 && results.isEmpty(), "first source was not selected again");
                    attached(current);
                    check(results.size() == 1 && results.remove(0) == null, "returned source did not attach");
                    showForSource(3, results::add);
                    current.listener.pending.onComplete(null);
                    check(current.source.id == 3 && results.isEmpty(), "second source lost its return output");
                    attached(current);
                    check(results.size() == 1 && results.remove(0) == null, "second return did not complete");
                    current = new Session();
                    LAST_OUTPUTS.clear();

                    showForSource(2, results::add);
                    current.listener.pending.onComplete(new IllegalStateException("activation failed"));
                    check(results.size() == 1 && results.remove(0) != null, "activation error hidden");
                    showForSource(2, results::add);
                    current.bindingGeneration++;
                    current.listener.pending.onComplete(null);
                    check(results.size() == 1 && results.remove(0) != null,
                            "late return overwrote a new binding");

                    current = new Session();
                    showForSource(2, results::add);
                    current.listener.pending.onComplete(null);
                    current.source = new DesktopDisplayInfo(3);
                    attached(current);
                    check(results.size() == 1 && results.remove(0) != null, "another source completed the return");
                    current.change = new Object();
                    showForSource(3, results::add);
                    check(results.size() == 1 && results.remove(0) != null, "pending exchange was disturbed");
                    current.change = null;
                    current.listener = null;
                    showForSource(3, results::add);
                    check(results.size() == 1 && results.remove(0) != null, "missing viewer silently ignored");
                    current = new Session();
                    current.error = "lease lost";
                    showForSource(2, results::add);
                    current.listener.pending.onComplete(null);
                    check(results.size() == 1 && results.remove(0) != null, "attachment error hidden");
                }
                """ + RuntimeSourceFixture.methods("DisplayPresentations", "showForSource", "showSource",
                        "returnOutput", "rememberOutput", "selectForAttachment"));
    }

    @Test public void latestOutputIsUnambiguousAcrossMultipleViewers() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId;
                    DesktopDisplayInfo(int id, String uniqueId) { this.id = id; this.uniqueId = uniqueId; }
                }
                static class Session {
                    DesktopDisplayInfo source;
                    boolean closed, outputAttachment = true;
                    Session(DesktopDisplayInfo source) { this.source = source; }
                }
                static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
                static final Map<String, Session> LAST_OUTPUTS = new LinkedHashMap<>();
                public static void verify() {
                    var source = new DesktopDisplayInfo(2, "source");
                    var next = new DesktopDisplayInfo(3, "next");
                    Session first = new Session(source), second = new Session(next);
                    SESSIONS.put("first", first); SESSIONS.put("second", second);
                    rememberOutput(first);
                    first.source = next;
                    check(returnOutput(source) == first, "replaced source lost its output");
                    second.source = source;
                    check(returnOutput(source) == second, "historical output overrode current binding");
                    rememberOutput(second);
                    second.source = next;
                    check(returnOutput(source) == second, "return did not use latest output");
                    check(returnOutput(new DesktopDisplayInfo(2, "replacement")) == null,
                            "reused Android id inherited an old output");
                }
                """ + RuntimeSourceFixture.methods("DisplayPresentations", "returnOutput", "rememberOutput", "forSource"));
    }

    @Test public void onlyUserReturnShowsTheOutputAndItCannotEnterAReplacementWorkspace() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                static class Log { static void i(String tag, String message) { } }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                static class TaskRepository {
                    interface ActionCallback { void onComplete(ActionResult result); }
                    record ActionResult(boolean success, String message) { }
                }
                static class DesktopDisplayTarget {
                    int workspaceDisplayId = 2;
                    Output output = new Output();
                    static class Output { String kind = "simulated"; }
                    boolean sameBinding(DesktopDisplayTarget other) { return other == this; }
                }
                static class DesktopSessionPolicy { }
                static class Workspace { String id = "original"; }
                static class DesktopSessionSnapshot {
                    boolean host = true;
                    Workspace workspace = new Workspace();
                    DesktopDisplayTarget target = new DesktopDisplayTarget();
                    DesktopSessionPolicy policy = new DesktopSessionPolicy();
                    boolean hasHost() { return host; }
                    int activeWorkspaceDisplayId() { return 2; }
                    int hostTaskId() { return 123; }
                    Workspace workspace() { return workspace; }
                    DesktopDisplayTarget target() { return target; }
                    DesktopSessionPolicy policy() { return policy; }
                }
                static class DesktopRuntimeBridge {
                    static DesktopSessionSnapshot current = new DesktopSessionSnapshot();
                    static DesktopSessionSnapshot getSessionSnapshot(int id) { return current; }
                }
                static class DesktopHomeRoleLease {
                    enum Phase { ACTIVE, RELEASING }
                    static class State {
                        Phase phase = Phase.ACTIVE;
                        boolean matches(DesktopDisplayTarget target) { return true; }
                    }
                    static State lease = new State();
                    static State snapshot() { return lease; }
                }
                static class DisplayPresentations {
                    static int shows;
                    static java.util.function.Consumer<Throwable> pending;
                    static void showForSource(int id, java.util.function.Consumer<Throwable> callback) {
                        check(id == 2, "wrong source"); shows++; pending = callback;
                    }
                }
                static class MagicDeskRuntime {
                    static int commands;
                    static void presentDesktopWorkspace(int id, int host, TaskRepository.ActionCallback callback) {
                        check(id == 2 && host == 123, "workspace command changed"); commands++;
                        if (callback != null) callback.onComplete(new TaskRepository.ActionResult(true, "ok"));
                    }
                }
                public static void verify() {
                    DesktopSessionSnapshot s = DesktopRuntimeBridge.current;
                    List<TaskRepository.ActionResult> results = new ArrayList<>();
                    check(showExistingSession(s.target, results::add), "user return not accepted");
                    check(MagicDeskRuntime.commands == 0 && results.isEmpty(), "workspace ran before output");
                    DisplayPresentations.pending.accept(null);
                    check(MagicDeskRuntime.commands == 1 && results.remove(0).success(), "workspace not shown");
                    check(presentExistingSession(s.target, s.policy, results::add), "recovery declined");
                    check(DisplayPresentations.shows == 1 && MagicDeskRuntime.commands == 2,
                            "internal recovery raised Viewer");
                    results.clear();
                    showExistingSession(s.target, results::add);
                    DisplayPresentations.pending.accept(new IllegalStateException("output unavailable"));
                    check(!results.remove(0).success() && MagicDeskRuntime.commands == 2, "failed output reordered tasks");
                    showExistingSession(s.target, results::add);
                    DesktopRuntimeBridge.current = new DesktopSessionSnapshot();
                    DesktopRuntimeBridge.current.workspace.id = "replacement";
                    DesktopRuntimeBridge.current.target = s.target;
                    DesktopRuntimeBridge.current.policy = s.policy;
                    DisplayPresentations.pending.accept(null);
                    check(!results.remove(0).success() && MagicDeskRuntime.commands == 2, "replacement workspace activated");
                    DesktopHomeRoleLease.lease.phase = DesktopHomeRoleLease.Phase.RELEASING;
                    check(!showExistingSession(s.target, results::add), "closing session accepted");
                    check(!showExistingSession(null, results::add), "missing target accepted");
                }
                """ + RuntimeSourceFixture.methods("DesktopSessionController",
                        "showExistingSession", "presentExistingSession", "matchingSession"));
    }
}
