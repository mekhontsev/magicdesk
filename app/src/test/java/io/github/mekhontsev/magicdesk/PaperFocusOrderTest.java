package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PaperFocusOrderTest {
    @Test public void tiledActivationUsesExistingOrderingGateway() throws Exception {
        RuntimeSourceFixture.verify(RuntimeSourceFixture.methods("DesktopTaskController",
                "focusThroughGateway") + """
            int mDisplayId=3, operation, target;
            List<Integer> order;
            Paper paper;
            static class TaskRepository {
                record ActionResult(boolean success,String message) {}
                interface ActionCallback { void onComplete(ActionResult result); }
            }
            static class DesktopWorkspaceCommand { static final int ACTIVATE=1,RESTORE_WORKSPACE=4; }
            static class Paper {
                boolean owns(int id){return id==3;}
                List<Integer> activationOrder(int id){return List.of(99,1,5,2,4,3);}
            }
            Paper paperPolicy(){return paper;}
            boolean isDesktopOwnedTask(int id){return id!=99;}
            boolean restoreTaskbarTask(int id){return false;}
            void concealTaskbarTask(int id){}
            TaskRepository.ActionCallback beginFocusTracking(int id,TaskRepository.ActionCallback callback){
                target=id; return callback;
            }
            static void completeActionCallback(TaskRepository.ActionCallback callback,boolean ok,String message){
                if(callback!=null)callback.onComplete(new TaskRepository.ActionResult(ok,message));
            }
            void sendWorkspaceCommand(int command,int display,List<Integer> ids,TaskRepository.ActionCallback callback){
                operation=command; order=ids;
                callback.onComplete(new TaskRepository.ActionResult(true,""));
            }
            public static void verify(){
                Fixture f=new Fixture();
                f.focusThroughGateway(1,List.of(3),3,null);
                check(f.operation==1 && f.order.equals(List.of(3)),"floating activation changed");
                f.paper=new Paper();
                f.focusThroughGateway(1,List.of(3),3,null);
                check(f.operation==4 && f.order.equals(List.of(1,5,2,4,3)) && f.target==3,
                        "tiling must order only owned tasks with the selected task last");
                f.focusThroughGateway(1,List.of(8),8,null);
                check(f.operation==1 && f.order.equals(List.of(8)),"non-tiled task reordered peers");
                f.focusThroughGateway(2,List.of(3,4),4,null);
                check(f.operation==2 && f.order.equals(List.of(3,4)),"demotion order changed");
            }
            """);
    }
}
