package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PaperDirectActivationTest {
    @Test public void activationWaitsForRevealAndRejectsFailedOrCancelledPlacement() throws Exception {
        RuntimeSourceFixture.verify(RuntimeSourceFixture.methods("DesktopTaskController",
                "activateThroughGateway") + """
            int activations;
            TaskRepository.ActionCallback pending;
            static class TaskRepository {
                static class Snapshot {}
                record ActionResult(boolean success, String message) {}
                interface ActionCallback { void onComplete(ActionResult result); }
            }
            static class DesktopWorkspaceQueue {
                interface Completion extends TaskRepository.ActionCallback { boolean isCurrent(); }
            }
            static class WindowLayoutPolicy {
                record Request(int taskId) {
                    static Request reveal(int id) { return new Request(id); }
                }
            }
            static class DesktopWorkspaceCommand { static final int ACTIVATE=1; }
            void applyWindowLayout(TaskRepository.Snapshot workspace, WindowLayoutPolicy.Request request,
                    DesktopWorkspaceQueue.Completion current, TaskRepository.ActionCallback callback) {
                check(request.taskId()==42,"reveal the requested column");
                pending=callback;
            }
            void focusThroughGateway(int command,List<Integer> ids,int focused,
                    TaskRepository.ActionCallback callback) {
                check(command==1 && ids.equals(List.of(42)) && focused==42,"activate requested task");
                activations++; callback.onComplete(new TaskRepository.ActionResult(true,""));
            }
            public static void verify() {
                Fixture f=new Fixture();
                List<TaskRepository.ActionResult> results=new ArrayList<>();
                boolean[] current={true};
                DesktopWorkspaceQueue.Completion completion=new DesktopWorkspaceQueue.Completion() {
                    public boolean isCurrent() { return current[0]; }
                    public void onComplete(TaskRepository.ActionResult result) { results.add(result); }
                };
                f.activateThroughGateway(new TaskRepository.Snapshot(),42,completion);
                check(f.activations==0 && results.isEmpty(),"activation waits for reveal");
                f.pending.onComplete(new TaskRepository.ActionResult(false,"placement failed"));
                check(results.size()==1 && !results.get(0).success && f.activations==0,
                        "failed placement must not activate an offscreen task");
                f.activateThroughGateway(new TaskRepository.Snapshot(),42,completion);
                f.pending.onComplete(new TaskRepository.ActionResult(true,""));
                check(f.activations==1 && results.size()==2 && results.get(1).success,
                        "successful reveal activates on the first request");
                f.activateThroughGateway(new TaskRepository.Snapshot(),42,completion);
                current[0]=false;
                f.pending.onComplete(new TaskRepository.ActionResult(true,""));
                check(f.activations==1 && results.size()==2,"stale placement cannot change focus");
            }
            """);
    }
}
