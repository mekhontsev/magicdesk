package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public final class TaskManagerActivityTest {
    @Test
    public void activeRefreshUsesFullObservationWithoutFreshQueries() throws Exception {
        verify("""
                Fixture f = new Fixture();
                DesktopRuntimeBridge.session = new DesktopSessionSnapshot(4, 4);
                TaskRepository.TaskEntry desktop = new TaskRepository.TaskEntry(41);
                TaskRepository.TaskEntry phone = new TaskRepository.TaskEntry(10);
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(
                        List.of(desktop, phone), List.of(phone), true, "");
                f.refresh(); f.mMonitor.complete();
                check(f.rendered.equals(List.of(desktop, phone)), "full task lists not retained/deduplicated");
                for (int i = 0; i < 3; i++) { f.refresh(false); f.mMonitor.complete(); }
                check(TaskRepository.loads == 0, "active refresh queried tasks");
                check(f.mMonitor.loads == 4, "resource monitoring cadence was lost");
                check(f.mHandler.scheduled == 4, "refresh completion lost scheduling ownership");
                """);
    }

    @Test
    public void unknownObservationIsNotEmptyAndDoesNotTriggerFallback() throws Exception {
        verify("""
                Fixture f = new Fixture();
                DesktopRuntimeBridge.session = new DesktopSessionSnapshot(4, 4);
                f.refresh(); f.mMonitor.complete();
                check(f.rendered == null, "unknown observation was rendered as empty");
                check("unknown".equals(f.mView.error), "unknown status was not reported");
                check(!f.mLoading, "unknown observation left refresh loading");
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(), List.of(), true, "");
                f.refresh(false); f.mMonitor.complete();
                check(f.rendered != null && f.rendered.isEmpty(), "observed empty snapshot was rejected");
                DesktopRuntimeBridge.session = new DesktopSessionSnapshot(-1, 4);
                MagicDeskRuntime.observed = null;
                f.refresh(); f.mMonitor.complete();
                check(TaskRepository.loads == 0, "host recreation caused a standalone query");
                """);
    }

    @Test
    public void standaloneQueriesAreExplicitAndNotPeriodic() throws Exception {
        verify("""
                Fixture f = new Fixture();
                TaskRepository.next = new TaskRepository.Snapshot(
                        List.of(new TaskRepository.TaskEntry(11)), List.of(), true, "");
                f.refresh(); TaskRepository.complete(); f.mMonitor.complete();
                for (int i = 0; i < 3; i++) { f.refresh(false); f.mMonitor.complete(); }
                check(TaskRepository.loads == 1, "standalone periodic refresh queried tasks");
                f.refresh(); TaskRepository.complete(); f.mMonitor.complete();
                check(TaskRepository.loads == 2, "explicit standalone refresh lost fresh query");
                """);
    }

    @Test
    public void deliveryRechecksSessionAndDiscardsStandaloneResult() throws Exception {
        verify("""
                Fixture f = new Fixture();
                TaskRepository.next = new TaskRepository.Snapshot(
                        List.of(new TaskRepository.TaskEntry(11)), List.of(), true, "");
                f.refresh();
                DesktopRuntimeBridge.session = new DesktopSessionSnapshot(4, 4);
                TaskRepository.TaskEntry live = new TaskRepository.TaskEntry(41);
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(live), List.of(), true, "");
                TaskRepository.complete(); f.mMonitor.complete();
                check(f.rendered.equals(List.of(live)), "standalone completion overwrote active tasks");
                check(f.mStandaloneSnapshot == null, "active session retained standalone result");
                f.refresh(false);
                DesktopRuntimeBridge.session = new DesktopSessionSnapshot(7, 7);
                TaskRepository.TaskEntry replacement = new TaskRepository.TaskEntry(71);
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(replacement), List.of(), true, "");
                f.mMonitor.complete();
                check(MagicDeskRuntime.requestedDisplay == 7, "delivery used an obsolete display");
                check(f.rendered.equals(List.of(replacement)), "delivery used an obsolete observation");
                f.refresh(false);
                DesktopRuntimeBridge.session = new DesktopSessionSnapshot(-1, -1);
                int previousRenders = f.renders;
                f.mMonitor.complete();
                check(f.renders == previousRenders, "stopped session resurrected standalone tasks");
                check("unknown".equals(f.mView.error), "missing standalone observation not unknown");
                """);
    }

    @Test
    public void stoppedOrInvalidatedRequestsCannotRender() throws Exception {
        verify("""
                Fixture f = new Fixture();
                DesktopRuntimeBridge.session = new DesktopSessionSnapshot(4, 4);
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(), List.of(), true, "");
                f.refresh(false); f.mLoadGeneration++; f.mMonitor.complete();
                check(f.renders == 0, "invalidated monitor request rendered");
                f.mLoading = false; f.refresh(false); f.mStarted = false; f.mMonitor.complete();
                check(f.renders == 0, "stopped activity rendered");
                Fixture outside = new Fixture();
                DesktopRuntimeBridge.session = new DesktopSessionSnapshot(-1, -1);
                outside.refresh(); outside.mLoadGeneration++; TaskRepository.complete();
                check(outside.mMonitor.loads == 0, "invalidated standalone query started monitoring");
                """);
    }

    @Test
    public void scheduledRefreshAndLifecycleRetainTheirOwnership() throws Exception {
        final String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "TaskManagerActivity.java"));
        assertTrue(source.contains("mScheduledRefresh = () -> refresh(false);"));
        final String stop = source.substring(source.indexOf("protected void onStop()"),
                source.indexOf("protected void onDestroy()"));
        assertTrue(stop.contains("mStandaloneSnapshot = null;"));
        assertTrue(stop.contains("mLoadGeneration++;"));
        assertTrue(stop.contains("mHandler.removeCallbacks(mScheduledRefresh);"));
        final String shell = source.substring(source.indexOf("public void onShellStateChanged("),
                source.indexOf("private void refresh()"));
        assertTrue(shell.contains("mStandaloneSnapshot = null;"));
        assertTrue(shell.contains("mLoadGeneration++;"));
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify(FIXTURE + "public static void verify() {\n" + scenario + "\n}\n"
                + RuntimeSourceFixture.methods("TaskManagerActivity", "refresh", "refreshMonitor",
                        "observationDisplayId", "allTasks", "finishRefresh"));
    }

    private static final String FIXTURE = """
            static final long REFRESH_INTERVAL_MILLIS = 3000;
            static final int PROCESS_MEMORY_REFRESH_CYCLES = 4;
            final Handler mHandler = new Handler();
            final Runnable mScheduledRefresh = () -> refresh(false);
            final Monitor mMonitor = new Monitor();
            final TaskView mView = new TaskView();
            boolean mStarted = true, mDestroyed, mLoading, mHasRenderedContent;
            int mLoadGeneration, mRefreshCycle, renders;
            TaskRepository.Snapshot mStandaloneSnapshot;
            List<TaskRepository.TaskEntry> rendered;
            void runOnUiThread(Runnable callback) { callback.run(); }
            void render(List<TaskRepository.TaskEntry> tasks, Monitor.Snapshot monitor) {
                rendered = tasks; renders++; mHasRenderedContent = true;
            }
            static class Handler {
                int scheduled;
                void removeCallbacks(Runnable callback) {}
                void postDelayed(Runnable callback, long delay) { scheduled++; }
            }
            static class TaskView {
                String error;
                void showWaiting() {}
                void showInitialLoading() {}
                void showUnavailable(String value) { error = value; }
            }
            static class Monitor {
                static class Snapshot {}
                int loads;
                java.util.function.Consumer<Snapshot> pending;
                void load(boolean memory, java.util.function.Consumer<Snapshot> callback) { loads++; pending = callback; }
                void complete() { var callback = pending; pending = null; callback.accept(new Snapshot()); }
            }
            static class ShellAccess { static boolean isReady() { return true; } }
            static class TaskRepository {
                static class TaskEntry { final int taskId; TaskEntry(int id) { taskId = id; } }
                record Snapshot(List<TaskEntry> tasks, List<TaskEntry> phoneTasks, boolean available, String error) {}
                static int loads;
                static Snapshot next = new Snapshot(List.of(), List.of(), true, "");
                static java.util.function.Consumer<Snapshot> pending;
                static void load(int display, java.util.function.Consumer<Snapshot> callback) { loads++; pending = callback; }
                static void complete() { var callback = pending; pending = null; callback.accept(next); }
            }
            record Target(int workspaceDisplayId) {}
            record DesktopSessionSnapshot(int activeWorkspaceDisplayId, int targetDisplay) {
                boolean hasHost() { return activeWorkspaceDisplayId >= 0; }
                Target target() { return targetDisplay < 0 ? null : new Target(targetDisplay); }
            }
            static class DesktopRuntimeBridge {
                static DesktopSessionSnapshot session = new DesktopSessionSnapshot(-1, -1);
                static DesktopSessionSnapshot getSessionSnapshot() { return session; }
            }
            static class MagicDeskRuntime {
                static TaskRepository.Snapshot observed;
                static int requestedDisplay;
                static TaskRepository.Snapshot observedTaskSnapshot(int display) { requestedDisplay = display; return observed; }
            }
            """;
}
