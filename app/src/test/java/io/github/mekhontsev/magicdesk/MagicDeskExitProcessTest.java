package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class MagicDeskExitProcessTest {
    @Test public void changedStartupIdentityEndsProcessAfterTasksAndHomeHandoff() throws Exception {
        RuntimeSourceFixture.verify("""
                static final List<String> events = new ArrayList<>();
                static boolean changed;
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
                static class ShellPrivilegePolicy { static boolean restartRequired(Host activity) { return changed; } }
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
                    check(events.equals(List.of("home", "task", "task")), "ordinary Exit killed cached process");
                    changed = true;
                    events.clear();
                    controller.openHomeAndFinishTasks();
                    check(events.equals(List.of("home", "task", "task", "exit")), "identity change ended process before cleanup");
                    events.clear();
                    controller.mActivity.failHome = controller.mActivity.failTasks = true;
                    controller.openHomeAndFinishTasks();
                    check(events.equals(List.of("home", "fallbackTask", "exit")), "failed UI cleanup cancelled Exit");
                }
                """);
    }
}
