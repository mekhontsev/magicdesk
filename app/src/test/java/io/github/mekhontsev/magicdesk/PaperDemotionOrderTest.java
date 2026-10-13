package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PaperDemotionOrderTest {
    @Test public void reflowPrecedesDemotionAndFailurePreservesWindow() throws Exception {
        RuntimeSourceFixture.verify(RuntimeSourceFixture.topLevelMethods("DesktopTaskController", "demoteTask") + """
            Set<Integer> mTaskbarConcealedTaskIds=new HashSet<>();
            DisplayState mDisplayTaskState=new DisplayState();
            TaskRepository.ActionCallback pending;
            int demotions;
            static class TaskRepository {
                static class Snapshot {}
                record ActionResult(boolean success,String message) {}
                interface ActionCallback {void onComplete(ActionResult result);}
            }
            static class DesktopWorkspaceQueue {
                interface Completion extends TaskRepository.ActionCallback {boolean isCurrent();}
            }
            static class DisplayState {List<Integer> lastVisibleTasks(){return List.of(1,2);}}
            static class TaskbarTaskOrder {
                static List<Integer> concealTask(TaskRepository.Snapshot s,int id,List<Integer> tasks,
                        Set<Integer> concealed,int home){return List.of(1,0,2);}
            }
            static class DesktopWorkspaceCommand {static final int DEMOTE=2;}
            static class Paper {boolean owns(int id){return id>0;}}
            static class WindowLayoutPolicy {
                record Request(int taskId){
                    static Request reveal(int id){return new Request(id);}
                    static Request observe(){return new Request(-1);}
                }
            }
            Paper paperPolicy(){return new Paper();}
            int currentDesktopHostTaskId(){return 0;}
            void clearPaperControls(){}
            void notifyTaskStackChanged(){}
            void restoreTaskbarTask(int id){mTaskbarConcealedTaskIds.remove(id);}
            static void completeActionCallback(TaskRepository.ActionCallback cb,boolean ok,String message){
                cb.onComplete(new TaskRepository.ActionResult(ok,message));
            }
            void applyWindowLayout(TaskRepository.Snapshot s,WindowLayoutPolicy.Request r,
                    DesktopWorkspaceQueue.Completion completion,TaskRepository.ActionCallback cb){
                check(mTaskbarConcealedTaskIds.contains(1),"departing column must be excluded before reflow");
                check(r.taskId()==2,"reflow must reveal the successor");pending=cb;
            }
            void focusThroughGateway(int operation,List<Integer> order,int target,TaskRepository.ActionCallback cb){
                check(operation==2 && target==2,"preserve demotion focus gateway");demotions++;
                cb.onComplete(new TaskRepository.ActionResult(true,""));
            }
            public static void verify(){
                for(boolean success:List.of(true,false)){
                    Fixture f=new Fixture();List<TaskRepository.ActionResult> results=new ArrayList<>();
                    boolean[] current={true};
                    DesktopWorkspaceQueue.Completion completion=new DesktopWorkspaceQueue.Completion(){
                        public boolean isCurrent(){return current[0];}
                        public void onComplete(TaskRepository.ActionResult r){results.add(r);}
                    };
                    f.demoteTask(new TaskRepository.Snapshot(),1,completion);
                    check(f.demotions==0,"window minimized before its gap closed");
                    f.pending.onComplete(new TaskRepository.ActionResult(success,"result"));
                    check(f.demotions==(success?1:0),"failed layout must not minimize");
                    check(f.mTaskbarConcealedTaskIds.contains(1)==success,"failed layout lost window");
                    check(results.size()==1 && results.get(0).success()==success,"lost completion");
                    f.demoteTask(new TaskRepository.Snapshot(),1,completion);current[0]=false;
                    int before=f.demotions;
                    f.pending.onComplete(new TaskRepository.ActionResult(true,"late"));
                    check(f.demotions==before,"cancelled layout triggered demotion");
                }
            }
            """);
    }
}
