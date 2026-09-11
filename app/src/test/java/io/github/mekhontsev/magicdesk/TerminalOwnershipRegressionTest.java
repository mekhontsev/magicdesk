package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Runs the registry ownership operations with lightweight window/PTY doubles. */
public final class TerminalOwnershipRegressionTest {
    @Test public void detachAndReattachRetainOnePtyAndRejectStaleWindowCleanup() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalSession session = acquire("terminal-a", ignored -> new ConsoleTerminalSession());
                    Activity first = new Activity(); ConsoleTerminalView oldView = new ConsoleTerminalView();
                    register(first, session, oldView, "terminal-a");
                    long generation = attachmentGeneration("terminal-a");
                    detach("terminal-a", first);
                    check(!hasWindowLocked("terminal-a", 0), "detached window remained registered");
                    check(oldView.detached && !first.finished, "detach waited for Activity destruction to release the View");
                    check(!session.closed, "detach terminated PTY");
                    check(acquire("terminal-a", null) == session, "attach replaced PTY");
                    Activity second = new Activity(); ConsoleTerminalView view = new ConsoleTerminalView();
                    register(second, session, view, "terminal-a");
                    check(hasWindowLocked("terminal-a", generation), "new attachment not acknowledged");
                    check(!hasWindowLocked("terminal-a", attachmentGeneration("terminal-a")), "old attachment acknowledged");
                    detach("terminal-a", first);
                    check(hasWindowLocked("terminal-a", generation), "stale Activity detached new window");
                    check(close("terminal-a"), "close rejected retained session");
                    check(session.closed && second.finished && view.detached, "close did not release all owners");
                    try { acquire("terminal-a", null); throw new AssertionError("expired attach created shell"); }
                    catch (IllegalStateException expected) { }
                }
                """);
    }

    @Test public void repeatedHideKeepsTheDetachedSessionAndRejectsOnlyMissingSessions() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalSession session = acquire("terminal-c", ignored -> new ConsoleTerminalSession());
                    Activity activity = new Activity(); ConsoleTerminalView view = new ConsoleTerminalView();
                    register(activity, session, view, "terminal-c");
                    check(hide("terminal-c"), "hide failed");
                    check(activity.finished && view.detached && !session.closed, "hide did not separate window and PTY");
                    check(hide("terminal-c") && acquire("terminal-c", null) == session, "repeat hide lost the session");
                    check(!hide("terminal-missing"), "hide accepted an unknown session");
                }
                """);
    }

    @Test public void replacingLiveViewDisconnectsOldViewBeforeItsActivityFinishes() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    ConsoleTerminalSession session = acquire("terminal-b", ignored -> new ConsoleTerminalSession());
                    Activity first = new Activity(); ConsoleTerminalView oldView = new ConsoleTerminalView();
                    register(first, session, oldView, "terminal-b");
                    Activity second = new Activity();
                    register(second, session, new ConsoleTerminalView(), "terminal-b");
                    check(first.finished && oldView.detached, "old presentation retained input/resize ownership");
                    check(!session.closed, "reattachment closed PTY");
                    detach("terminal-b", first);
                    check(hasWindowLocked("terminal-b", 0), "old destruction unregistered new view");
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                static class WeakReference<T> extends java.lang.ref.WeakReference<T> { WeakReference(T v) { super(v); } }
                interface Function<T,R> extends java.util.function.Function<T,R> {}
                static class ConsoleTerminalSession { interface Listener {} boolean closed; void close() { closed=true; } }
                static class ConsoleTerminalView { boolean detached; void attach(Object a,Object b) { detached=true; } }
                static class Activity { boolean finished; boolean isDestroyed() { return false; }
                    int getTaskId() { return 1; } void finishAndRemoveTask() { finished=true; } }
                static class DesktopAutomationEventJournal { static void record(String t,String o,boolean s,String d) {} }
                static class MagicDeskRuntime { static void refreshNotification() {} }
                static class TerminalNotifications { static void cancel(String id) {} }
                static class Entry implements ConsoleTerminalSession.Listener {
                    final ConsoleTerminalSession session; long attachmentGeneration;
                    WeakReference<Activity> activity=new WeakReference<>(null);
                    WeakReference<ConsoleTerminalView> view=new WeakReference<>(null);
                    Entry(String id,Function<ConsoleTerminalSession.Listener,ConsoleTerminalSession> f) { session=f.apply(this); }
                }
                static Map<String,Entry> ENTRIES = new LinkedHashMap<>();
                static java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong();
                static <T> T callOnMain(Callable<T> c) { try { return c.call(); } catch(Exception e) { throw new RuntimeException(e); } }
                """ + RuntimeSourceFixture.methods("ConsoleTerminalRegistry", "acquire", "register",
                        "nextId", "validId", "detach", "close", "hide", "find", "findLocked", "pruneLocked",
                        "attachmentGeneration", "hasWindowLocked");
    }
}
