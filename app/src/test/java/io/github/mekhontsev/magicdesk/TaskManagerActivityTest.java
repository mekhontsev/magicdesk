package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;

public final class TaskManagerActivityTest {
    @Test public void activeRefreshUnionsWorkspacesWithoutPeriodicTaskQueries() throws Exception {
        verify("""
                Fixture f = new Fixture();
                DesktopRuntimeBridge.displays = Set.of(4, 5);
                var a = new TaskRepository.TaskEntry(41); var b = new TaskRepository.TaskEntry(51);
                var phone = new TaskRepository.TaskEntry(10);
                MagicDeskRuntime.observed.put(4, new TaskRepository.Snapshot(List.of(a, phone), List.of(phone), true, ""));
                MagicDeskRuntime.observed.put(5, new TaskRepository.Snapshot(List.of(b, phone), List.of(phone), true, ""));
                f.refresh(); f.mMonitor.complete();
                check(new HashSet<>(f.mView.rendered).equals(Set.of(a,b,phone)), "workspace merge lost tasks");
                for (int i=0;i<3;i++) { f.refresh(false); f.mMonitor.complete(); }
                check(TaskRepository.loads==0 && f.mMonitor.loads==4, "periodic task query or missing resource sample");
                """);
    }
    @Test public void unknownWindowObservationDoesNotSuppressProcessView() throws Exception {
        verify("""
                Fixture f = new Fixture(); DesktopRuntimeBridge.displays = Set.of(4);
                f.refresh(); f.mMonitor.complete();
                check(f.mView.renders==1 && f.mView.rendered.isEmpty(), "resource view was suppressed");
                check(f.mView.error.contains("unavailable") && !f.mLoading, "unknown window observation lost");
                check(TaskRepository.loads==0, "unknown observation triggered fallback query");
                """);
    }
    @Test public void standaloneQueriesAreExplicitAndStaleResultsCannotRender() throws Exception {
        verify("""
                Fixture f = new Fixture();
                f.refresh(); TaskRepository.complete(); f.mMonitor.complete();
                for(int i=0;i<3;i++) { f.refresh(false); f.mMonitor.complete(); }
                check(TaskRepository.loads==1 && f.mView.renders==4, "standalone periodic task query");
                f.refresh(false); f.mLoadGeneration++; f.mMonitor.complete();
                check(f.mView.renders==4, "stale callback rendered");
                f.mLoading=false; f.refresh(false); f.mStarted=false; f.mMonitor.complete();
                check(f.mView.renders==4, "stopped activity rendered");
                """);
    }
    @Test public void deliveryRechecksWorkspaceAndDoesNotResurrectStandaloneTasks() throws Exception {
        verify("""
                Fixture f = new Fixture(); f.refresh();
                DesktopRuntimeBridge.displays=Set.of(4);
                var live = new TaskRepository.TaskEntry(41);
                MagicDeskRuntime.observed.put(4,new TaskRepository.Snapshot(List.of(live),List.of(),true,""));
                TaskRepository.complete(); f.mMonitor.complete();
                check(f.mView.rendered.equals(List.of(live)), "standalone completion won over Desktop");
                DesktopRuntimeBridge.displays=Set.of(); f.refresh(false); f.mMonitor.complete();
                check(f.mView.rendered.isEmpty() && !f.mView.error.isEmpty(), "stale standalone snapshot revived");
                """);
    }
    @Test public void focusUsesExistingOwnershipGateways() throws Exception {
        var source=Files.readString(Path.of(RuntimeSourceFixture.MAIN+"TaskManagerActions.java"));
        assertTrue(source.contains("MagicDeskRuntime.focusDesktopTask("));
        assertTrue(source.contains("ApplicationTaskPlacement.controlIndependent("));
        assertFalse(source.contains("TaskRepository.bringToFront("));
        var activity=Files.readString(Path.of(RuntimeSourceFixture.MAIN+"TaskManagerActivity.java"));
        assertTrue(activity.contains("mHandler.removeCallbacks(mScheduledRefresh)"));
        assertTrue(activity.contains("generation != mSessionGeneration"));
    }
    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final long REFRESH_INTERVAL_MILLIS=3000;
                final Handler mHandler=new Handler();
                final Runnable mScheduledRefresh=()->refresh(false);
                final Monitor mMonitor=new Monitor();
                final TaskView mView=new TaskView();
                Object mTmux;
                String mTmuxError="";
                boolean mStarted=true,mDestroyed,mLoading;
                int mLoadGeneration;
                TaskRepository.Snapshot mStandaloneSnapshot;
                void refreshTmux() {}
                int getTaskId() { return -1; }
                void runOnUiThread(Runnable r) { r.run(); }
                static class Handler { void removeCallbacks(Runnable r){} void postDelayed(Runnable r,long delay){} }
                static class TaskView {
                    List<TaskRepository.TaskEntry> rendered; String error; int renders;
                    void showUnavailable(String error) { this.error=error; }
                    void render(List<TaskRepository.TaskEntry> tasks,Object monitor,String error) {
                        rendered=tasks; this.error=error; renders++;
                    }
                }
                static class Monitor {
                    int loads; java.util.function.Consumer<Object> callback;
                    void load(java.util.function.Consumer<Object> c) { loads++;callback=c; }
                    void complete() { callback.accept(new Object()); }
                }
                static class TaskRepository {
                    static class TaskEntry { final int taskId; TaskEntry(int id){taskId=id;} }
                    record Snapshot(List<TaskEntry> tasks,List<TaskEntry> phoneTasks,boolean available,String error) {
                        Snapshot(List<TaskEntry> t,boolean a,String e){this(t,List.of(),a,e);}
                    }
                    static int loads; static java.util.function.Consumer<Snapshot> callback;
                    static void load(int display,java.util.function.Consumer<Snapshot> c){loads++;callback=c;}
                    static void complete(){callback.accept(new Snapshot(List.of(new TaskEntry(1)),true,""));}
                }
                static class DesktopRuntimeBridge {
                    static Set<Integer> displays=Set.of();
                    static Set<Integer> workspaceDisplayIds(){return displays;}
                }
                static class MagicDeskRuntime {
                    static Map<Integer,TaskRepository.Snapshot> observed=new HashMap<>();
                    static TaskRepository.Snapshot observedTaskSnapshot(int id){return observed.get(id);}
                }
                static class ShellAccess { static boolean isReady(){return true;} }
                static class ConsoleTerminalRegistry { static List<Object> list(){return List.of();} }
                static class TaskManagerApplications {
                    static List<TaskRepository.TaskEntry> collect(Object c,List<TaskRepository.TaskEntry> tasks,
                            Object terminals,Object tmux,Object monitor,int own){return tasks;}
                }
                public static void verify(){
                """+scenario+"\n}\n"+RuntimeSourceFixture.methods("TaskManagerActivity",
                        "refresh","refreshMonitor","current","taskSnapshot","allTasks"));
    }
}
