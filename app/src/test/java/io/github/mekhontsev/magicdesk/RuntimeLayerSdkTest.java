package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class RuntimeLayerSdkTest {
    @Test public void displayRemovalCompletionDoesNotRequireDesktop() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Session {
                    int refreshes, reconciles;
                    void refreshOwnership() { refreshes++; }
                    void reconcileHomeLease() { reconciles++; }
                }
                Session mDesktopSession;
                List<Runnable> posted = new ArrayList<>();
                int taskUpdates;
                void postIfAlive(Runnable action) { posted.add(action); }
                void updateDesktopTasks() { taskUpdates++; }
                void drain() { for (Runnable action : posted) action.run(); posted.clear(); }
                """ + RuntimeSourceFixture.methods("MagicDeskRuntimeService",
                        "desktopTransitionFinished", "refreshDesktopTasks") + """
                public static void verify() {
                    Fixture runtime = new Fixture();
                    runtime.desktopTransitionFinished();
                    runtime.refreshDesktopTasks();
                    runtime.drain();
                    check(runtime.mDesktopSession == null && runtime.taskUpdates == 0,
                            "independent display completion promoted Desktop");
                    Session session = new Session();
                    runtime.mDesktopSession = session;
                    runtime.desktopTransitionFinished();
                    runtime.refreshDesktopTasks();
                    runtime.drain();
                    check(session.refreshes == 2 && session.reconciles == 1 && runtime.taskUpdates == 2,
                            "active Desktop no longer reconciles transitions");
                    runtime.desktopTransitionFinished();
                    runtime.refreshDesktopTasks();
                    runtime.mDesktopSession = null;
                    runtime.drain();
                    check(runtime.taskUpdates == 2, "queued callback used a destroyed coordinator");
                }
                """);
    }

    @Test public void desktopRequestFailsBeforeStartingServiceOnUnsupportedSdk() throws Exception {
        RuntimeSourceFixture.verify(capabilities() + """
                static class Intent { Intent(Object context, Class<?> type) {} }
                static class MagicDeskRuntimeService {}
                static class Context { int starts; void startForegroundService(Intent intent) { starts++; } }
                """ + RuntimeSourceFixture.methods("MagicDeskRuntime", "start") + """
                public static void verify() {
                    Context context = new Context();
                    try { start(context); throw new AssertionError("unsupported Desktop accepted"); }
                    catch (UnsupportedOperationException expected) {}
                    check(context.starts == 0, "started service before version rejection");
                    Build.VERSION.SDK_INT = 35;
                    start(context);
                    check(context.starts == 1, "Android 15 Desktop no longer starts");
                }
                """);
    }

    @Test public void directDesktopIntentCannotTakeDownIndependentServices() throws Exception {
        RuntimeSourceFixture.verify(capabilities() + """
                static final int NOTIFICATION_ID = 1, START_NOT_STICKY = 2;
                static class Intent { String action; Intent(String value) { action = value; } }
                static class MagicDeskRuntime {
                    static int preparingDisplay(Intent i) { return -1; }
                    static boolean isToolsStart(Intent i) { return i != null && "tools".equals(i.action); }
                    static boolean isAutomationStart(Intent i) { return i != null && "automation".equals(i.action); }
                }
                static class MagicDeskMcpPreferences {
                    static boolean enabled = true;
                    static boolean isEnabled(Object context) { return enabled; }
                }
                static class ShellAccess { static boolean isReady() { return true; } }
                static class DesktopHomeRoleLease { static Object snapshot() { return null; } }
                static class Mcp { int reconciles; void reconcile() { reconciles++; } }
                static class Input { void reconcileRuntime() {} }
                static class DesktopRuntimeBridge {
                    static SessionSnapshot getSessionSnapshot(int displayId) { return new SessionSnapshot(); }
                }
                static class SessionSnapshot { int inputDisplayId() { return -1; } }
                static class Session { void schedulePhoneTaskRecovery() {} }
                Mcp mMcpRuntime = new Mcp();
                Set<Integer> mPreparingDisplays = new HashSet<>();
                static class Tasks { void prepare(int id) {} }
                Tasks mDesktopTaskRuntime = new Tasks();
                Input mDisplayInput;
                Session mDesktopSession;
                boolean mToolsRequested, mInitialized, stopped;
                void startForeground(int id, Object notification) {}
                Object buildNotification() { return new Object(); }
                void updateNotification() {}
                void stopSelf() { stopped = true; }
                void initialize() {
                    RuntimeCapabilities.requireDesktop();
                    mInitialized = true; mDisplayInput = new Input(); mDesktopSession = new Session();
                }
                int desktopDisplayId() { return -1; }
                void updateDesktopTasks() {}
                void reportDesktopPrepared() {}
                """ + RuntimeSourceFixture.methods("MagicDeskRuntimeService", "onStartCommand")
                        .replace("android.os.Build", "Build") + """
                public static void verify() {
                    Fixture service = new Fixture();
                    service.onStartCommand(new Intent("desktop"), 0, 1);
                    check(!service.mInitialized && !service.stopped, "Desktop broke automation");
                    check(service.mMcpRuntime.reconciles == 1, "MCP not reconciled");
                    service.onStartCommand(new Intent("tools"), 0, 2);
                    check(service.mToolsRequested && !service.mInitialized, "tools promoted Desktop");
                    MagicDeskMcpPreferences.enabled = false;
                    service.onStartCommand(null, 0, 3);
                    check(!service.stopped, "independent tools stopped");
                    Fixture unused = new Fixture();
                    unused.onStartCommand(new Intent("desktop"), 0, 1);
                    check(unused.stopped, "unused service remained alive");
                    Build.VERSION.SDK_INT = 35;
                    MagicDeskMcpPreferences.enabled = true;
                    Fixture automation = new Fixture();
                    automation.onStartCommand(new Intent("automation"), 0, 1);
                    check(!automation.mInitialized && !automation.stopped, "automation promoted Desktop");
                    check(automation.mMcpRuntime.reconciles == 1, "automation not started");
                    MagicDeskMcpPreferences.enabled = false;
                    automation.onStartCommand(new Intent("automation"), 0, 2);
                    check(automation.stopped, "disabled automation stayed alive");
                    service.onStartCommand(new Intent("desktop"), 0, 4);
                    check(service.mInitialized, "supported Desktop did not initialize");
                }
                """);
    }

    private static String capabilities() throws Exception {
        return """
                static class Build { static class VERSION { static int SDK_INT = 34; } }
                static class RuntimeCapabilities {
                    static final int DESKTOP_MIN_SDK = 35;
                """ + RuntimeSourceFixture.methods("RuntimeCapabilities", "supportsDesktop", "requireDesktop")
                        .replace("android.os.Build", "Build") + "}\n";
    }
}
