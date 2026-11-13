package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Runs the registry ownership operations with lightweight window/PTY doubles. */
public final class TerminalOwnershipRegressionTest {
    @Test public void terminalIdsDoNotRestartWithTheProcess() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    for (int i = 0; i < 100; i++) {
                        String id = nextId();
                        check(validId(id), "generated id rejected");
                        check(id.matches("terminal-[0-9a-f]{32}"), "process-local counter used as id");
                        check(ids.add(id), "terminal id reused");
                    }
                }
                """);
    }
    @Test public void notificationResumeUsesFocusOrderAndRetainsDetachedPtys() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    check(mostRecent() == null, "empty registry offered a terminal");
                    Activity first = new HostActivity(), second = new HostActivity();
                    ConsoleTerminalSession a = acquire("terminal-a", ignored -> new ConsoleTerminalSession());
                    check(mostRecent() == null, "unseen background session became MRU");
                    register(first, a, new ConsoleTerminalView(), "terminal-a");
                    check(mostRecent() == null, "unfocused window became MRU");
                    focused("terminal-a", first);
                    ConsoleTerminalSession b = acquire("terminal-b", ignored -> new ConsoleTerminalSession());
                    register(second, b, new ConsoleTerminalView(), "terminal-b");
                    focused("terminal-b", second);
                    check(mostRecent().id.equals("terminal-b"), "did not choose latest focus");
                    focused("terminal-a", first);
                    check(mostRecent().id.equals("terminal-a"), "used creation order instead of focus");
                    check(new ArrayList<>(ENTRIES.keySet()).equals(List.of("terminal-a", "terminal-b")),
                            "focus reordered the shared session catalog");
                    detach("terminal-a", first);
                    check(mostRecent().id.equals("terminal-a") && !a.closed, "lost retained MRU after detach");
                    focused("terminal-b", first);
                    check(mostRecent().id.equals("terminal-a"), "foreign window stole focus record");
                    close("terminal-a");
                    check(mostRecent().id.equals("terminal-b"), "did not fall back after MRU ended");
                    close("terminal-b");
                    check(mostRecent() == null, "ended sessions remained resumable");
                    check(MagicDeskRuntime.refreshes == 5, "notification did not follow registrations/detach/close");
                }
                """);
    }

    @Test public void staleAndFinishingWindowFocusCannotReplaceRecentTerminal() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    Activity old = new HostActivity(), replacement = new HostActivity(), other = new HostActivity();
                    ConsoleTerminalSession a = acquire("terminal-a", ignored -> new ConsoleTerminalSession());
                    register(old, a, new ConsoleTerminalView(), "terminal-a");
                    focused("terminal-a", old);
                    register(replacement, a, new ConsoleTerminalView(), "terminal-a");
                    ConsoleTerminalSession b = acquire("terminal-b", ignored -> new ConsoleTerminalSession());
                    register(other, b, new ConsoleTerminalView(), "terminal-b");
                    focused("terminal-b", other);
                    focused("terminal-a", old);
                    replacement.finished = true;
                    focused("terminal-a", replacement);
                    focused(null, other);
                    check(mostRecent().id.equals("terminal-b"), "stale or finishing window changed MRU");
                    check(MagicDeskRuntime.refreshes == 3, "focus unnecessarily refreshed notification");
                }
                """);
    }

    @Test public void detachAndReattachRetainOnePtyAndRejectStaleWindowCleanup() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalSession session = acquire("terminal-a", ignored -> new ConsoleTerminalSession());
                    Activity first = new HostActivity(); ConsoleTerminalView oldView = new ConsoleTerminalView();
                    register(first, session, oldView, "terminal-a");
                    long generation = attachmentGeneration("terminal-a");
                    detach("terminal-a", first);
                    check(!hasWindowLocked("terminal-a", 0), "detached window remained registered");
                    check(oldView.detached && !first.finished, "detach waited for Activity destruction to release the View");
                    check(!session.closed, "detach terminated PTY");
                    check(acquire("terminal-a", null) == session, "attach replaced PTY");
                    Activity second = new HostActivity(); ConsoleTerminalView view = new ConsoleTerminalView();
                    register(second, session, view, "terminal-a");
                    check(hasWindowLocked("terminal-a", generation), "new attachment not acknowledged");
                    check(!hasWindowLocked("terminal-a", attachmentGeneration("terminal-a")), "old attachment acknowledged");
                    detach("terminal-a", first);
                    check(hasWindowLocked("terminal-a", generation), "stale Activity detached new window");
                    check(close("terminal-a"), "close rejected retained session");
                    check(session.closed && !second.finished && second.released == 1 && view.detached,
                            "ending PTY must release the binding, not its window");
                    try { acquire("terminal-a", null); throw new AssertionError("expired attach created shell"); }
                    catch (IllegalStateException expected) { }
                }
                """);
    }

    @Test public void repeatedHideKeepsTheDetachedSessionAndRejectsOnlyMissingSessions() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalSession session = acquire("terminal-c", ignored -> new ConsoleTerminalSession());
                    Activity activity = new HostActivity(); ConsoleTerminalView view = new ConsoleTerminalView();
                    register(activity, session, view, "terminal-c");
                    check(hide("terminal-c"), "hide failed");
                    check(activity.finished && view.detached && !session.closed, "hide did not separate window and PTY");
                    check(hide("terminal-c") && acquire("terminal-c", null) == session, "repeat hide lost the session");
                    check(!hide("terminal-missing"), "hide accepted an unknown session");
                }
                """);
    }

    @Test public void transferReleasesInputButKeepsBothWindowsAlive() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalSession session = acquire("terminal-b", ignored -> new ConsoleTerminalSession());
                    Activity first = new HostActivity(); ConsoleTerminalView oldView = new ConsoleTerminalView();
                    register(first, session, oldView, "terminal-b");
                    Activity second = new HostActivity();
                    register(second, session, new ConsoleTerminalView(), "terminal-b");
                    check(!first.finished && first.released == 1 && oldView.detached,
                            "transfer must empty the old window and revoke input/resize");
                    check(!session.closed, "reattachment closed PTY");
                    detach("terminal-b", first);
                    check(hasWindowLocked("terminal-b", 0), "old destruction unregistered new view");
                }
                """);
    }

    @Test public void oneWindowSwitchesSessionsWithoutClosingEitherPty() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    Activity window = new HostActivity(); ConsoleTerminalView view = new ConsoleTerminalView();
                    ConsoleTerminalSession a = acquire("terminal-a", ignored -> new ConsoleTerminalSession());
                    ConsoleTerminalSession b = acquire("terminal-b", ignored -> new ConsoleTerminalSession());
                    ENTRIES.get("terminal-a").tmuxSessionId = "$1";
                    register(window, a, view, "terminal-a");
                    long generation = attachmentGeneration("terminal-a");
                    register(window, b, view, "terminal-b");
                    check(!hasWindowLocked("terminal-a", 0) && hasWindowLocked("terminal-b", 0),
                            "one window was bound to two sessions");
                    check(window.released == 1 && view.detached && !window.finished, "old binding not released");
                    check(!a.closed && !b.closed, "switch killed a PTY or tmux client");
                    register(window, a, view, "terminal-a");
                    check(hasWindowLocked("terminal-a", generation), "switch back was not a new attachment");
                    close("terminal-b");
                    check(window.released == 2 && hasWindowLocked("terminal-a", 0),
                            "background PTY exit detached the selected session");
                }
                """);
    }

    @Test public void repeatedRegistrationDoesNotRevokeInputOrCreateFalseAttachmentReceipt() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    Activity window = new HostActivity(); ConsoleTerminalView view = new ConsoleTerminalView();
                    ConsoleTerminalSession a = acquire("terminal-a", ignored -> new ConsoleTerminalSession());
                    register(window, a, view, "terminal-a");
                    long generation = attachmentGeneration("terminal-a");
                    register(window, a, view, "terminal-a");
                    check(!view.detached && window.released == 0, "unchanged binding was revoked");
                    check(attachmentGeneration("terminal-a") == generation, "spurious attachment receipt");
                }
                """);
    }

    @Test public void tmuxCloseDisconnectsClientButRotationAndStaleWindowsDoNot() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalSession session = acquire("terminal-d", ignored -> new ConsoleTerminalSession());
                    ENTRIES.get("terminal-d").tmuxSessionId = "$2";
                    Activity old = new HostActivity();
                    register(old, session, new ConsoleTerminalView(), "terminal-d");
                    detach("terminal-d", old);
                    check(!session.closed, "configuration destruction closed tmux client");
                    Activity next = new HostActivity();
                    register(next, session, new ConsoleTerminalView(), "terminal-d");
                    old.finished = true;
                    detach("terminal-d", old);
                    check(!session.closed, "stale finishing window closed replacement client");
                    next.finished = true;
                    detach("terminal-d", next);
                    check(session.closed && !ENTRIES.containsKey("terminal-d"), "caption close retained invisible tmux client");
                    session = acquire("terminal-e", ignored -> new ConsoleTerminalSession());
                    ENTRIES.get("terminal-e").tmuxSessionId = "$3";
                    register(new HostActivity(), session, new ConsoleTerminalView(), "terminal-e");
                    check(hide("terminal-e") && session.closed, "explicit detach retained tmux client");
                }
                """);
    }

    @Test public void closuresIdentifyTheirOwnerAndDoNotCloseOtherTmuxClients() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    Activity window = new HostActivity();
                    for (int i = 1; i <= 3; i++) {
                        String id = "terminal-" + i;
                        ConsoleTerminalSession session = acquire(id, ignored -> new ConsoleTerminalSession());
                        session.pid = 100 + i;
                        ENTRIES.get(id).tmuxSessionId = "$" + i;
                        register(window, session, new ConsoleTerminalView(), id);
                    }
                    ENTRIES.get("terminal-1").onFinished();
                    close("terminal-2");
                    check(ENTRIES.size() == 1 && !ENTRIES.get("terminal-3").session.closed,
                            "one client exit closed another session");
                    check(hasWindowLocked("terminal-3", 0), "background exit detached selected session");
                    check(DesktopAutomationEventJournal.events.contains(
                            "closed:true:terminalId=terminal-1 backend=termux pid=101 tmuxSessionId=$1 reason=transport-eof"),
                            "EOF lost process identity or became an explicit close");
                    check(DesktopAutomationEventJournal.events.contains(
                            "closed:true:terminalId=terminal-2 backend=termux pid=102 tmuxSessionId=$2 reason=requested"),
                            "explicit close lost its cause");
                    int count = DesktopAutomationEventJournal.events.size();
                    close("terminal-1");
                    check(count == DesktopAutomationEventJournal.events.size(), "duplicate close emitted another cause");
                    hide("terminal-3");
                    check(DesktopAutomationEventJournal.events.get(count).endsWith("reason=client-detached"),
                            "client detach was recorded as EOF");
                }
                """);
    }

    @Test public void runtimeAndWindowClosureCausesCoverEveryExecutor() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    for (String backend : List.of("app", "shell", "termux")) {
                        ConsoleTerminalSession session = acquire("terminal-" + backend, ignored -> new ConsoleTerminalSession());
                        session.executor = new Backend(backend);
                    }
                    closeAll();
                    check(ENTRIES.isEmpty(), "runtime cleanup left a PTY");
                    for (String backend : List.of("app", "shell", "termux")) {
                        check(DesktopAutomationEventJournal.events.stream().anyMatch(event -> event.contains(
                                "backend=" + backend) && event.endsWith("reason=runtime-stopped")),
                                "runtime cleanup did not identify executor " + backend);
                    }
                    var session = acquire("terminal-window", ignored -> new ConsoleTerminalSession());
                    ENTRIES.get("terminal-window").tmuxSessionId = "$4";
                    Activity window = new HostActivity();
                    register(window, session, new ConsoleTerminalView(), "terminal-window");
                    window.finished = true;
                    detach("terminal-window", window);
                    check(DesktopAutomationEventJournal.events.get(DesktopAutomationEventJournal.events.size()-1)
                            .endsWith("reason=window-closed"), "window closure was recorded as EOF");
                }
                """);
    }

    @Test public void diagnosticBirthIdentitySurvivesLivePidReset() throws Exception {
        RuntimeSourceFixture.verify("""
                final Object mLock = new Object();
                long mProcessId = -1;
                record PtyEndpoint(long processId, long startTicks, String tty) { }
                PtyEndpoint mOutputEndpoint;
                """ + RuntimeSourceFixture.methods("ConsoleTerminalSession", "diagnosticIdentity", "processId") + """
                public static void verify() {
                    Fixture session = new Fixture();
                    check(session.diagnosticIdentity().equals("pid=-1"), "unstarted session invented a PID");
                    session.mProcessId = 101;
                    check(session.diagnosticIdentity().equals("pid=101"), "missing optional endpoint lost live PID");
                    session.mOutputEndpoint = new PtyEndpoint(101, 900, "/dev/pts/4");
                    session.mProcessId = -1;
                    check(session.processId() == -1, "diagnostics changed live process semantics");
                    check(session.diagnosticIdentity().equals("pid=101 startTicks=900 tty=/dev/pts/4"),
                            "EOF lost process birth identity");
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                static class WeakReference<T> extends java.lang.ref.WeakReference<T> { WeakReference(T v) { super(v); } }
                interface Function<T,R> extends java.util.function.Function<T,R> {}
                interface Consumer<T> extends java.util.function.Consumer<T> {}
                static class Backend { final String wireName; Backend(String name) { wireName=name; } }
                static class ConsoleTerminalSession {
                    interface Listener {} boolean closed; long pid=42; Backend executor=new Backend("termux");
                    void close() { closed=true; } Backend backend() { return executor; }
                    String diagnosticIdentity() { check(!closed,"identity read after cleanup"); return "pid="+pid; }
                }
                static class ConsoleTerminalView { boolean detached; void attach(Object a,Object b) { detached=true; } }
                static class HostActivity extends Activity implements TerminalWindowAttachment.Host {}
                static class Activity {
                    boolean finished; int released; public void onTerminalDetached() { released++; }
                    boolean isDestroyed() { return false; }
                    boolean isFinishing() { return finished; }
                    int getTaskId() { return 1; } void finishAndRemoveTask() { finished=true; } }
                static class DesktopAutomationEventJournal {
                    static List<String> events=new ArrayList<>();
                    static void record(String t,String o,boolean s,String d) { events.add(o+":"+s+":"+d); }
                }
                static class ConsoleTerminalRegistry {
                    static boolean close(String id,String reason) { return Fixture.close(id,reason); }
                }
                static class MagicDeskRuntime { static int refreshes; static void refreshNotification() { refreshes++; } }
                static class TerminalNotifications { static class Session { void close() {} } }
                record Snapshot(String id) {}
                static class Entry implements ConsoleTerminalSession.Listener {
                    final String id; final ConsoleTerminalSession session; long lastFocusSequence;
                    String tmuxSessionId = "";
                    final TerminalWindowAttachment window=new TerminalWindowAttachment();
                    final TerminalNotifications.Session notifications=new TerminalNotifications.Session();
                    Entry(String id,Function<ConsoleTerminalSession.Listener,ConsoleTerminalSession> f) { this.id=id; session=f.apply(this); }
                    Snapshot snapshot(String id) { return new Snapshot(id); }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalRegistry", "recordLifecycle", "onFinished") + """
                }
                static Map<String,Entry> ENTRIES = new LinkedHashMap<>();
                static long focusSequence;
                static <T> T callOnMain(Callable<T> c) { try { return c.call(); } catch(Exception e) { throw new RuntimeException(e); } }
                """ + "static " + RuntimeSourceFixture.nestedClass("TerminalWindowAttachment", "TerminalWindowAttachment")
                + RuntimeSourceFixture.methods("ConsoleTerminalRegistry", "acquire", "register",
                        "nextId", "validId", "detach", "close", "closeAll", "hide", "find", "findLocked", "pruneLocked",
                        "attachmentGeneration", "hasWindowLocked", "focused", "mostRecent");
    }
}
