package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PaperFocusBorderTest {
    @Test public void borderWaitsForGeometryAndExcludesConcealedTasks() throws Exception {
        RuntimeSourceFixture.verify(
                RuntimeSourceFixture.methods("DesktopTaskController", "updatePaperControls") + """
            int mObservedFocusedTaskId=1, mFocusingTaskId=1, mDisplayId=3;
            boolean mLayoutRefreshQueued=false, mRunning=true;
            final Queue mWorkspaceQueue=new Queue();
            final States mTaskRuntimeStates=new States();
            final NativeBounds mNativeWindowBounds=new NativeBounds();
            final Paper paper=new Paper();
            static class Rect {}
            static class Task {
                int taskId=1; boolean visible=true; Rect bounds=new Rect();
                boolean isBoundedFreeform(){return true;}
                boolean hasCrossPackageTopActivity(){return false;}
            }
            static class TaskRepository { static class Snapshot { List<Task> tasks=List.of(new Task()); } }
            static class Queue { boolean running; boolean isRunning(){return running;} }
            static class DesktopTaskRuntimeState {
                Object pending; boolean fullscreen;
                Object boundsTransition(){return pending;}
                boolean isFullscreenTransition(){return fullscreen;}
            }
            static class States {
                DesktopTaskRuntimeState state=new DesktopTaskRuntimeState();
                DesktopTaskRuntimeState find(int id){return state;}
            }
            static class NativeBounds { Rect getTaskbarMaximizedBounds(){return new Rect();} }
            static class Paper {
                boolean owns(int id){return true;}
                boolean canNavigate(int direction){return true;}
            }
            static class MagicDeskSettings {
                boolean paperActiveBorder=true, paperEdgeButtons=true;
                static MagicDeskSettings load(){return new MagicDeskSettings();}
            }
            static class DesktopTaskbarHost {
                static Rect border; static boolean navigation;
                static void setActiveWindowBorder(int display,Rect bounds,Rect area){border=bounds;}
                static void setPaperNavigation(int display,Rect area,boolean prev,boolean next,
                        java.util.function.IntConsumer action){navigation=prev||next;}
            }
            static class DesktopPaperWindowController { enum Command { PREVIOUS, NEXT } }
            void updatePaperFocusShields(TaskRepository.Snapshot workspace,Paper paper,Rect area,boolean idle){}
            Set<Integer> concealed=new HashSet<>();
            Set<Integer> paperConcealedTasks(List<Task> tasks){return concealed;}
            Paper paperPolicy(){return paper;}
            Task findTask(List<Task> tasks,int id){return tasks.stream().filter(t->t.taskId==id).findFirst().orElse(null);}
            void arrangePaperWindows(DesktopPaperWindowController.Command command,int id,Object callback){}
            public static void verify() {
                Fixture f=new Fixture();
                TaskRepository.Snapshot snapshot=new TaskRepository.Snapshot();
                f.updatePaperControls(snapshot);
                check(DesktopTaskbarHost.border==snapshot.tasks.get(0).bounds,
                        "confirmed focus border waited for queue bookkeeping");
                f.mWorkspaceQueue.running=true;
                f.updatePaperControls(snapshot);
                check(DesktopTaskbarHost.border==null && !DesktopTaskbarHost.navigation,
                        "controls displayed stale geometry during operation");
                f.mWorkspaceQueue.running=false;
                f.concealed.add(1);
                f.updatePaperControls(snapshot);
                check(DesktopTaskbarHost.border==null,"concealed task retained a border");
                f.concealed.clear();
                f.mFocusingTaskId=2;
                f.updatePaperControls(snapshot);
                check(DesktopTaskbarHost.border==null,"border guessed unconfirmed focus");
                f.mFocusingTaskId=1;
                f.mTaskRuntimeStates.state.pending=new Object();
                f.updatePaperControls(snapshot);
                check(DesktopTaskbarHost.border==null,"border displayed stale transitioning bounds");
                f.mTaskRuntimeStates.state.pending=null;
                f.mTaskRuntimeStates.state.fullscreen=true;
                f.updatePaperControls(snapshot);
                check(DesktopTaskbarHost.border==null,"border displayed during fullscreen transition");
                f.mTaskRuntimeStates.state.fullscreen=false;
                f.updatePaperControls(snapshot);
                check(DesktopTaskbarHost.border!=null,"confirmed geometry did not restore border");
            }
            """);
    }
}
