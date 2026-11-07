package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class IndependentApplicationsDialogTest {
    @Test public void capturesUiCatalogBeforeLoadingTasksAndMetadataOnWorker() throws Exception {
        verify("""
            var activity = new Activity();
            var selected = ApplicationCatalog.known;
            load(activity, new DesktopDisplayInfo(), Fixture::completed);
            check(ApplicationCatalog.reads == 1 && callbacks == 0, "catalog must be captured before dispatch");
            ApplicationCatalog.known = List.of(new AppItem("replacement"));
            expectedKnown = selected;
            work();
            check(metadataReads == 1 && callbacks == 0, "metadata must load off UI, without publishing from worker");
            publish();
            check(callbacks == 1 && failure == null && result.size() == 1, "missing UI result");
            check(result.get(0).title().equals("window") && result.get(0).detail().equals("detail"), "task presentation lost");
            """);
    }

    @Test public void taskAndDisplayFailuresReturnToUiWithoutMetadataWork() throws Exception {
        verify("""
            TaskRepository.snapshot.available = false;
            load(new Activity(), new DesktopDisplayInfo(), Fixture::completed); work(); publish();
            check(callbacks == 1 && failure instanceof IOException && result.isEmpty(), "unavailable tasks hidden");
            check(failure.getMessage().equals("task query failed") && metadataReads == 0, "unavailable snapshot read metadata");
            TaskRepository.snapshot.available = true;
            DesktopDisplayCatalog.available = false;
            load(new Activity(), new DesktopDisplayInfo(), Fixture::completed); work(); publish();
            check(callbacks == 2 && failure instanceof IOException && metadataReads == 0, "stale display admitted");
            check(failure.getMessage().equals("display removed"), "display identity failure lost");
            """);
    }

    @Test public void coldCatalogDoesNotHideTasksAndEmptyTasksRemainSuccessful() throws Exception {
        verify("""
            ApplicationCatalog.known = expectedKnown = List.of();
            load(new Activity(), new DesktopDisplayInfo(), Fixture::completed); work(); publish();
            check(result.size() == 1 && failure == null && metadataReads == 1, "cold catalog hid a live task");
            TaskRepository.snapshot.tasks = List.of();
            load(new Activity(), new DesktopDisplayInfo(), Fixture::completed); work(); publish();
            check(callbacks == 2 && result.isEmpty() && failure == null, "empty snapshot is not an error");
            """);
    }

    private static void verify(String body) throws Exception {
        RuntimeSourceFixture.verify(STUBS
                + RuntimeSourceFixture.methods("IndependentApplicationsDialog", "load", "entries")
                + "public static void verify() throws Exception { " + body + " }");
    }

    private static final String STUBS = """
        static boolean ui = true;
        static final ArrayDeque<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>();
        static void work() { ui=false; try { worker.remove().run(); } finally { ui=true; } }
        static void publish() { check(ui, "publish on worker"); main.remove().run(); }
        static class Activity {
            void runOnUiThread(Runnable runnable) { check(!ui, "worker result expected"); main.add(runnable); }
        }
        static class DesktopDisplayInfo { int id=7; String uniqueId="display-7"; }
        record AppItem(String label) { }
        record Entry(TaskRepository.TaskEntry task, AppItem app, String title, String detail) { }
        interface Loaded { void complete(List<Entry> entries, Throwable error); }
        static int callbacks, metadataReads;
        static List<Entry> result;
        static Throwable failure;
        static List<AppItem> expectedKnown;
        static void completed(List<Entry> entries, Throwable error) {
            check(ui, "completion off UI"); callbacks++; result=entries; failure=error;
        }
        static class TaskCommandQueue { static void execute(Runnable runnable) { worker.add(runnable); } }
        static class DesktopDisplayCatalog {
            static boolean available=true;
            static void require(int id, String uniqueId) throws IOException {
                check(!ui && id==7 && uniqueId.equals("display-7"), "display query scope/thread");
                if (!available) throw new IOException("display removed");
            }
        }
        static class TaskRepository {
            record TaskEntry(int id) { }
            static class Snapshot {
                boolean available=true; String error="task query failed";
                List<TaskEntry> tasks=List.of(new TaskEntry(1));
            }
            static final Snapshot snapshot=new Snapshot();
            static Snapshot loadAllNow() { check(!ui, "blocking task query on UI"); return snapshot; }
        }
        static class DesktopRuntimeBridge { static boolean hasWorkspace(int id) { return false; } }
        static class ApplicationTaskPlacement {
            static TaskRepository.Snapshot independentSnapshot(TaskRepository.Snapshot snapshot, int id, boolean active) {
                check(!ui && id==7 && !active, "independent scope changed"); return snapshot;
            }
        }
        static class ApplicationCatalog {
            static List<AppItem> known=List.of(new AppItem("cached")); static int reads;
            static ApplicationCatalog get(Activity activity) { check(ui, "catalog accessed off UI"); return new ApplicationCatalog(); }
            List<AppItem> androidApps(boolean freeform) { check(ui && freeform, "catalog snapshot off UI"); reads++; return known; }
        }
        static class LauncherAppRepository {
            LauncherAppRepository(Activity activity) { check(!ui, "metadata resolution on UI"); }
            int profile() { return 0; }
            AppItem findOrLoadTask(List<AppItem> known, TaskRepository.TaskEntry task, boolean freeform) {
                check(!ui && known==expectedKnown && freeform, "worker did not retain captured catalog");
                metadataReads++; return new AppItem("window");
            }
        }
        static class RunningTasks {
            static List<TaskRepository.TaskEntry> select(List<TaskRepository.TaskEntry> tasks, int profile, int display, Object home) {
                check(profile==0 && display==-1 && home==null, "profile/task selection changed"); return tasks;
            }
        }
        static class TaskTitle {
            static String resolve(Activity activity, AppItem app, TaskRepository.TaskEntry task) { return app.label(); }
            static String detail(Activity activity, TaskRepository.TaskEntry task) { return "detail"; }
        }
        """;
}
