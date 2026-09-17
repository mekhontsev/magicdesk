package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplaySwitchControllerTest {
    @Test public void pointerChoiceUsesTheSameImageAndInputOperation() throws Exception {
        verify("""
                DisplaySwitchController.show(new DesktopShellActivity(0)); drain();
                check(DisplaySwitchOperation.selected == -1, "opening picker switched displays");
                var panel = DisplaySwitchPanel.last;
                panel.choose.accept(1);
                check(DisplaySwitchOperation.selected == 1 && panel.closed, "pointer did not commit through operation");
                """);
    }

    @Test public void pointerCancelAndLateClickDoNotChangeDisplays() throws Exception {
        verify("""
                DisplaySwitchController.show(new DesktopShellActivity(0)); drain();
                var panel = DisplaySwitchPanel.last;
                panel.cancel.run(); panel.choose.accept(1);
                check(DisplaySwitchOperation.selected == -1 && panel.closed, "cancelled picker committed");
                check(DisplayManager.listeners == 0, "display listener retained");
                """);
    }

    @Test public void desktopViewedElsewhereSwitchesItsOutputRatherThanItsSource() throws Exception {
        verify("""
                DisplayPresentations.viewer = new DisplayPresentations.Session(display(1), display(0));
                DisplaySwitchController.show(new DesktopShellActivity(1)); drain();
                DisplaySwitchPanel.last.choose.accept(1);
                check(DisplaySwitchOperation.output == 0, "virtual source used as physical output");
                """);
    }

    @Test public void keyboardReleaseWhileLoadingStillCommitsWithoutAPanel() throws Exception {
        verify("""
                DisplaySwitchController.advanceForInput(false);
                DisplaySwitchController.commit(); drain();
                check(DisplaySwitchOperation.selected == 1, "keyboard commit lost while loading");
                check(DisplaySwitchPanel.last == null, "keyboard release created an orphan panel");
                """);
    }

    @Test public void closedHostDoesNotShowALatePointerPanel() throws Exception {
        verify("""
                var host = new DesktopShellActivity(0);
                DisplaySwitchController.show(host); host.closed = true; drain();
                check(DisplaySwitchPanel.last == null && DisplayManager.listeners == 0, "closed host retained picker");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final Queue<Runnable> pending = new ArrayDeque<>();
                static void drain() { while (!pending.isEmpty()) pending.remove().run(); }
                static class Looper { static Object getMainLooper() { return null; } }
                static class Handler { Handler(Object looper) {} void post(Runnable runnable) { pending.add(runnable); } }
                static class DisplayManager {
                    static int listeners;
                    interface DisplayListener { void onDisplayAdded(int id); void onDisplayChanged(int id); void onDisplayRemoved(int id); }
                    void registerDisplayListener(DisplayListener listener, Handler handler) { listeners++; }
                    void unregisterDisplayListener(DisplayListener listener) { listeners--; }
                }
                static class Context {
                    <T> T getSystemService(Class<T> type) { return type.cast(new DisplayManager()); }
                    String getString(int id, Object... args) { return "label"; }
                }
                static class Activity extends Context {}
                static class DesktopShellActivity extends Activity {
                    final int display; boolean closed;
                    DesktopShellActivity(int display) { this.display = display; }
                    int getCurrentDisplayId() { return display; }
                    boolean isActivityUnavailable() { return closed; }
                }
                static class MagicDeskApplication { static Context applicationContext() { return new Context(); } }
                static class MagicDeskRuntime {
                    static int inputDisplayId() { return 0; }
                    static int readyInputDisplayId() { return 0; }
                    static boolean inputTransitioning() { return false; }
                }
                static class DesktopDisplayInfo {
                    final int id; final String uniqueId, name;
                    DesktopDisplayInfo(int id) { this.id = id; uniqueId = name = "display" + id; }
                }
                static DesktopDisplayInfo display(int id) { return new DesktopDisplayInfo(id); }
                static class DesktopDisplayCatalog {
                    static DesktopDisplayInfo[] read() { return new DesktopDisplayInfo[] { display(0), display(1) }; }
                }
                static class TaskCommandQueue { static void execute(Runnable runnable) { pending.add(runnable); } }
                static class TaskRepository {
                    static class Task { int displayId; }
                    static class Snapshot { boolean available = true; List<Task> tasks = List.of(); }
                    static Snapshot loadAllNow() { return new Snapshot(); }
                }
                static class DesktopManagedTaskPolicy { static boolean isControllableApplicationTask(TaskRepository.Task task) { return true; } }
                static class DesktopRuntimeBridge { static boolean hasWorkspace(int id) { return true; } }
                static class DisplayPresentations {
                    static Session viewer;
                    record Session(DesktopDisplayInfo source, DesktopDisplayInfo output) { static final boolean visible = true; }
                    static Session forSource(int id) { return viewer != null && viewer.source.id == id ? viewer : null; }
                    static Session forOutput(int id) { return viewer != null && viewer.output.id == id ? viewer : null; }
                    static boolean canSwitchOutput(DesktopDisplayInfo source, DesktopDisplayInfo output) { return true; }
                }
                static class DisplaySwitchPanel {
                    static DisplaySwitchPanel last;
                    final java.util.function.IntConsumer choose; final Runnable cancel; boolean closed;
                    DisplaySwitchPanel(int id, Activity fallback, List<String> labels, DesktopShellActivity host,
                            java.util.function.IntConsumer choose, Runnable cancel) {
                        this.choose = choose; this.cancel = cancel; last = this;
                    }
                    void select(int index) {}
                    void close() { closed = true; }
                }
                static class DisplaySwitchOperation {
                    static int selected = -1, output = -1;
                    DisplaySwitchOperation(Context context, DesktopDisplayInfo output, DesktopDisplayInfo source,
                            java.util.function.Consumer<Throwable> completion) {
                        selected = source.id; DisplaySwitchOperation.output = output.id;
                    }
                    void start() {}
                }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                static class CompatibilityDiagnostics { static void record(String id, String label, String text, Throwable error) {} }
                static class Toast {
                    static int LENGTH_LONG; static Toast makeText(Context context, String text, int duration) { return new Toast(); }
                    void show() {}
                }
                static class R { static class string {
                    static int display_switch_this, display_desktop_active, display_no_desktop,
                        display_switch_apps, display_tasks_unknown, display_switch_failed;
                } }
                public static void verify() {
                """ + scenario + "}\nstatic "
                + RuntimeSourceFixture.nestedClass("DisplaySwitchController", "DisplaySwitchController"),
                "DisplaySwitchHistory");
    }
}
