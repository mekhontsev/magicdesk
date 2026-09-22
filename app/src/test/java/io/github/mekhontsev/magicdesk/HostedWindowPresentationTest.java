package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedWindowPresentationTest {
    @Test public void presentationAndRecoveryShareReservationsButRetainExactHostPlacement() throws Exception {
        RuntimeSourceFixture.verify("static " + RuntimeSourceFixture.nestedClass("HostedWindowPresentation", "HostedWindowPresentation")
                .replace("WeakReference<", "java.lang.ref.WeakReference<") + """
            static class Context { }
            static class Activity extends Context {
                final int task, display;
                int geometry;
                boolean destroyed, finishing;
                Activity(int task, int display) { this.task = task; this.display = display; this.geometry = task; }
                boolean isDestroyed() { return destroyed; }
                boolean isFinishing() { return finishing; }
                Display getDisplay() { return new Display(display); }
                int getTaskId() { return task; }
            }
            record Display(int id) { int getDisplayId() { return id; } }
            static class Looper { static Object getMainLooper() { return null; } }
            static class Handler {
                static final List<Runnable> pending = new ArrayList<>();
                Handler(Object looper) { }
                void post(Runnable work) { pending.add(work); }
                static void drain() { while (!pending.isEmpty()) pending.remove(0).run(); }
            }
            static class TaskCommandQueue { static void execute(Runnable work) { work.run(); } }
            static class ShellAccess { static boolean ready; static boolean isReady() { return ready; } }
            static class Intent {
                long window;
                Intent putExtra(String key, Object value) { if (key.equals("window")) window = (Long) value; return this; }
            }
            static class X11Activity {
                static final String SESSION = "session", WINDOW = "window";
                static Intent windowIntent(Context context, X11Sessions.Session session, long window) {
                    return new Intent().putExtra(WINDOW, window);
                }
            }
            static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
            static class SystemBarInsets { static void preserveCaption(Activity activity, Intent intent) { } }
            record DesktopLaunchPresentation(int geometry) { }
            static class ToolApplications {
                record WindowPlacement(int target, String uniqueId, int presentation) { }
                static int opens, restoredDisplay, restoredGeometry;
                static long restoredWindow;
                static boolean fail;
                static boolean defer;
                static BuiltInWindowLauncher.Callback pending;
                static WindowPlacement windowPlacement(int display, int task) throws IOException {
                    return new WindowPlacement(display, "display-" + display, task);
                }
                static DesktopLaunchPresentation replacementPresentation(Activity activity, WindowPlacement placement) {
                    return new DesktopLaunchPresentation(activity.geometry);
                }
                static void openSibling(Activity activity, Intent intent, BuiltInWindowLauncher.Callback done) {
                    opens++; done.onComplete(fail ? new IOException("unavailable") : null);
                }
                static void open(Context context, Intent intent, int target, String identity, BuiltInWindowLauncher.Callback done) {
                    open(context, intent, target, identity, -1, done);
                }
                static void open(Context context, Intent intent, int target, String identity,
                        DesktopLaunchPresentation presentation, BuiltInWindowLauncher.Callback done) {
                    open(context, intent, target, identity, presentation.geometry(), done);
                }
                static void open(Context context, Intent intent, int target, String identity, int geometry,
                        BuiltInWindowLauncher.Callback done) {
                    check(identity.equals("display-" + target), "stable display identity");
                    opens++; restoredDisplay = target; restoredGeometry = geometry; restoredWindow = intent.window;
                    if (defer) pending = done;
                    else done.onComplete(fail ? new IOException("unavailable") : null);
                }
            }
            static class X11Sessions {
                enum State { READY, CLOSED }
                record Window(long id) { }
                static class Session implements HostedWindowPresentation.Session {
                    int changes, failures, otherHost = -1;
                    State state = State.READY;
                    List<Window> windows = List.of(new Window(1), new Window(2), new Window(3));
                    String id() { return "session"; }
                    public boolean ready() { return state == State.READY; }
                    public boolean containsWindow(long id) { return windows.stream().anyMatch(item -> item.id() == id); }
                    public Intent windowIntent(Context context, long id) { return new Intent().putExtra("window", id); }
                    State state() { return state; }
                    List<Window> windows() { return windows; }
                    public int hostTaskId(long window) { return otherHost; }
                    public void presentationChanged() { changes++; }
                    public void presentationFailed(Throwable error) { failures++; }
                }
            }
            public static void verify() {
                var session = new X11Sessions.Session();
                var presentation = new HostedWindowPresentation(new Context(), session);
                check(!presentation.present(1), "no host or placement yet");
                var host = new Activity(11, 7);
                presentation.host(host); Handler.drain();
                check(session.changes == 1, "placement availability wakes presentation");
                check(presentation.present(1), "live host, no focus prerequisite or shell");
                check(!presentation.present(1) && ToolApplications.opens == 1, "duplicate catalog delivery");
                presentation.claim(2);
                check(!presentation.present(2), "launch host already owns window");
                host.destroyed = true;
                check(!presentation.present(3), "no implicit elevation for background launch");
                ShellAccess.ready = true;
                check(presentation.present(3), "verified destination survives destroyed host");
                check(!presentation.present(1), "catalog alone cannot duplicate an existing host");

                var other = new Activity(22, 9);
                presentation.host(other); Handler.drain();
                int before = ToolApplications.opens;
                host.geometry = 99;
                presentation.hostRemoved(host, 1, true);
                host.geometry = 100;
                presentation.hostRemoved(host, 1, true);
                check(ToolApplications.opens == before, "recovery waits for teardown, not a timer");
                Handler.drain();
                check(ToolApplications.opens == before + 1 && ToolApplications.restoredDisplay == 7
                        && ToolApplications.restoredGeometry == 99 && ToolApplications.restoredWindow == 1,
                        "recovery captures final geometry before teardown, not old placement or last focused sibling");
                check(!presentation.present(1), "recovery keeps catalog reservation");

                presentation.host(other); Handler.drain();
                before = ToolApplications.opens;
                presentation.hostRemoved(other, 2, true);
                session.windows = List.of(new X11Sessions.Window(1));
                presentation.retain(Set.of(1L)); Handler.drain();
                check(ToolApplications.opens == before, "client destruction cancels recovery");
                session.windows = List.of(new X11Sessions.Window(1), new X11Sessions.Window(2));

                presentation.host(other); Handler.drain();
                session.otherHost = 33;
                presentation.hostRemoved(other, 2, true); Handler.drain();
                check(ToolApplications.opens == before, "another live host prevents duplication");
                session.otherHost = -1;

                presentation.host(other); Handler.drain();
                presentation.hostRemoved(other, 0, false); Handler.drain();
                check(ToolApplications.opens == before, "whole desktop viewer is not restored");
                presentation.host(other); Handler.drain();
                presentation.hostRemoved(other, 2, false); Handler.drain();
                check(ToolApplications.opens == before, "recreation does not restore a second host");

                presentation.host(other); Handler.drain();
                ToolApplications.fail = true;
                presentation.hostRemoved(other, 2, true); Handler.drain();
                check(ToolApplications.opens == before + 1 && session.failures == 1, "restore error reported");
                check(!presentation.present(2), "failed recovery cannot cause a launch loop");
                ToolApplications.fail = false;

                presentation.host(other); Handler.drain();
                ShellAccess.ready = false;
                presentation.hostRemoved(other, 2, true); Handler.drain();
                check(ToolApplications.opens == before + 1 && session.failures == 2, "no implicit shell startup");
                ShellAccess.ready = true;

                presentation.host(other); Handler.drain();
                presentation.hostRemoved(other, 2, true);
                session.state = X11Sessions.State.CLOSED; Handler.drain();
                check(ToolApplications.opens == before + 1, "session stop suppresses pending recovery");
                session.state = X11Sessions.State.READY;
                presentation.host(other); Handler.drain();
                presentation.hostRemoved(other, 2, true);
                presentation.close(); Handler.drain();
                check(ToolApplications.opens == before + 1 && !presentation.present(5), "Exit cancels all pending presentations");

                var retained = new X11Sessions.Session();
                var retainedPresentation = new HostedWindowPresentation(new Context(), retained);
                retainedPresentation.host(other); Handler.drain();
                ToolApplications.defer = true;
                retainedPresentation.hostRemoved(other, 2, true); Handler.drain();
                var late = ToolApplications.pending;
                check(late != null, "replacement launch is in flight");
                retained.windows = List.of(new X11Sessions.Window(1));
                retainedPresentation.retain(Set.of(1L));
                late.onComplete(new IOException("task vanished during launch"));
                check(retained.failures == 0 && retained.state() == X11Sessions.State.READY,
                        "closed client cancels replacement failure without affecting its surviving sibling");

                retainedPresentation.host(other); Handler.drain();
                retainedPresentation.hostRemoved(other, 1, true); Handler.drain();
                ToolApplications.pending.onComplete(new IOException("real launch failure"));
                check(retained.failures == 1, "a surviving client still reports a real launch failure");

                retainedPresentation.host(other); Handler.drain();
                retainedPresentation.hostRemoved(other, 1, true); Handler.drain();
                retainedPresentation.close();
                ToolApplications.pending.onComplete(new IOException("session closed during launch"));
                check(retained.failures == 1, "session closure cancels in-flight presentation errors");
            }
            """);
    }
}
