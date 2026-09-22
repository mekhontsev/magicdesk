package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class MagicDeskExitProcessTest {
    @Test public void exitDisablesGraphicalPresentationBeforeRemovingAnyHost() throws Exception {
        RuntimeSourceFixture.verify("""
            static final List<String> events = new ArrayList<>();
            static class Host { void showSessionStatus(String status) { } }
            static class Activity { String getString(int id) { return "exit"; } }
            static class R { static class string { static int status_exiting; } }
            static class Log { static void i(String tag, String text) { } }
            static class MagicDeskRuntime { static void clearParkedDesktopTasks() { } }
            static class GraphicalSessions { static void prepareForExit() { events.add("stop-presentation"); } }
            static class BuiltInWindowRegistry {
                static void finishAll(Runnable next) { events.add("finish-windows"); next.run(); }
            }
            static class Controller {
                boolean mOperationInProgress;
                final Host mHost = new Host();
                final Activity mActivity = new Activity();
                final String TAG = "test";
                void startExit() { events.add("cleanup"); }
            """ + RuntimeSourceFixture.methods("MagicDeskSessionController", "exit") + """
            }
            public static void verify() {
                var controller = new Controller();
                controller.exit(); controller.exit();
                check(events.equals(List.of("stop-presentation", "finish-windows", "cleanup")), "exit ordering: " + events);
            }
            """);
    }

    @Test public void stopCompletesOnlyAfterSharedResourcesCloseOnce() throws Exception {
        RuntimeSourceFixture.verify("""
                static final List<String> events = new ArrayList<>();
                static class Resource {
                    final String name;
                    Resource(String name) { this.name = name; }
                    void stop() { events.add(name); }
                    void destroy() { events.add(name); }
                    void close() { events.add(name); }
                }
                static class Handler {
                    final List<Runnable> pending = new ArrayList<>();
                    boolean post(Runnable action) { pending.add(action); return true; }
                    void removeCallbacksAndMessages(Object token) { pending.clear(); events.add("handler"); }
                    void dispatch() { pending.remove(0).run(); }
                }
                static class MagicDeskRuntime { static void detach(Object service) { events.add("detach"); } }
                static class ShellAccess { static void removeStateListener(Object listener) { events.add("listener"); } }
                static class ConsoleTerminalRegistry { static void closeAll() { events.add("terminals"); } }
                static class GraphicalSessions { static void closeAll() { events.add("graphics"); } }
                static class AutomationCommandRuntime { static void closeCurrent() { events.add("cli"); } }
                static class Service {
                    boolean mDestroyed;
                    Object mShellStateListener;
                    final Handler mHandler = new Handler();
                    Resource mDisplayCoordinator = new Resource("displays");
                    Resource mDisplayInput = new Resource("input");
                    Resource mMcpRuntime = new Resource("mcp");
                    Runnable released;
                    void releaseDesktopTaskSession(Runnable completion) { released = completion; }
                    void destroyDesktopRuntime() { events.add("desktop"); }
                """ + RuntimeSourceFixture.methods("MagicDeskRuntimeService", "prepareForStop", "closeRuntime") + """
                }
                public static void verify() {
                    Service service = new Service();
                    service.prepareForStop(() -> events.add("complete"));
                    check(events.isEmpty(), "shutdown skipped task release acknowledgement");
                    service.released.run();
                    check(events.isEmpty(), "service cleanup ran on task worker");
                    service.mHandler.dispatch();
                    check(events.equals(List.of("detach", "listener", "displays", "desktop", "input",
                            "terminals", "graphics", "mcp", "cli", "handler", "complete")), "shutdown order: " + events);
                    service.closeRuntime();
                    check(events.size() == 11, "onDestroy repeated completed cleanup");
                    check(service.mDestroyed && service.mDisplayInput == null && service.mMcpRuntime == null,
                            "runtime retained resources");
                }
                """);
    }

    @Test public void exitAlwaysEndsProcessAfterTasksAndHomeHandoff() throws Exception {
        RuntimeSourceFixture.verify("""
                static final List<String> events = new ArrayList<>();
                static class Intent {
                    static final String ACTION_MAIN = "main", CATEGORY_HOME = "home";
                    static final int FLAG_ACTIVITY_NEW_TASK = 1;
                    Intent(String action) {}
                    Intent addCategory(String category) { return this; }
                    Intent addFlags(int flags) { return this; }
                }
                static class ActivityManager {
                    class AppTask { void finishAndRemoveTask() { events.add("task"); } }
                    List<AppTask> getAppTasks() { return List.of(new AppTask(), new AppTask()); }
                }
                static class Host {
                    boolean failHome, failTasks;
                    void startActivity(Intent intent) {
                        events.add("home");
                        if (failHome) throw new IllegalStateException();
                    }
                    ActivityManager getSystemService(Class<?> type) {
                        if (failTasks) throw new IllegalStateException();
                        return new ActivityManager();
                    }
                    void finishAndRemoveTask() { events.add("fallbackTask"); }
                    String getString(int id, String detail) { return detail; }
                }
                static class android { static class os { static class Process {
                    static int myPid() { return 123; }
                    static void killProcess(int pid) { check(pid == 123, "foreign process"); events.add("exit"); }
                } } }
                static class R { static class string { static int status_exit_failed; } }
                static class Log { static void w(String tag, String text, Throwable error) {} }
                static class Controller {
                    final Host mActivity = new Host();
                    final String TAG = "test";
                    void reportExitFailure(String code, String message, Throwable error) {}
                """ + RuntimeSourceFixture.methods("MagicDeskSessionController", "openHomeAndFinishTasks") + """
                }
                public static void verify() {
                    Controller controller = new Controller();
                    controller.openHomeAndFinishTasks();
                    check(events.equals(List.of("home", "task", "task", "exit")), "Exit retained cached process or skipped cleanup");
                    events.clear();
                    controller.mActivity.failHome = controller.mActivity.failTasks = true;
                    controller.openHomeAndFinishTasks();
                    check(events.equals(List.of("home", "fallbackTask", "exit")), "failed UI cleanup cancelled Exit");
                }
                """);
    }
}
