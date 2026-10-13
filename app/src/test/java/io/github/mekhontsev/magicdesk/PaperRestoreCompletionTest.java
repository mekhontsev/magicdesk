package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PaperRestoreCompletionTest {
    @Test public void controlsRefreshAfterRestoreAcknowledgement() throws Exception {
        RuntimeSourceFixture.verify(
                RuntimeSourceFixture.methods("DesktopTaskController", "enqueueWorkspaceOperation")
                + RuntimeSourceFixture.nestedClass("DesktopWorkspaceQueue", "DesktopWorkspaceQueue")
                        .replace("final class DesktopWorkspaceQueue", "static final class DesktopWorkspaceQueue")
                + """
            int mGeneration=1, refreshes;
            boolean running=true, borderVisible, navigationEnabled;
            final Handler mHandler=new Handler();
            final DesktopWorkspaceQueue mWorkspaceQueue=new DesktopWorkspaceQueue(mHandler::post);
            static class Handler {
                final ArrayDeque<Runnable> work=new ArrayDeque<>();
                void post(Runnable action) { work.addLast(action); }
                void drain() { while (!work.isEmpty()) work.removeFirst().run(); }
            }
            static class TaskRepository {
                record ActionResult(boolean success, String message) {}
                interface ActionCallback { void onComplete(ActionResult result); }
                static class Snapshot { boolean available=true; String error=""; }
                static void load(int display, java.util.function.Consumer<Snapshot> callback) {
                    callback.accept(new Snapshot());
                }
            }
            interface WorkspaceOperation {
                void run(TaskRepository.Snapshot workspace,DesktopWorkspaceQueue.Completion completion);
            }
            boolean isWorkspaceRequestCurrent(int display,int generation) {
                return running && display==6 && generation==mGeneration;
            }
            TaskRepository.Snapshot desktopWorkspaceSnapshot(TaskRepository.Snapshot snapshot) {
                return snapshot;
            }
            static void completeActionCallback(TaskRepository.ActionCallback callback,boolean ok,String message) {
                if (callback!=null) callback.onComplete(new TaskRepository.ActionResult(ok,message));
            }
            void drawControls() {
                borderVisible=!mWorkspaceQueue.isRunning();
                navigationEnabled=!mWorkspaceQueue.isRunning();
            }
            void scheduleRefresh(long delay) {
                check(delay==0,"completion must not introduce a delay");
                refreshes++;
                mHandler.post(this::drawControls);
            }
            public static void verify() {
                for (boolean success : List.of(true,false)) {
                    Fixture f=new Fixture();
                    List<TaskRepository.ActionCallback> pending=new ArrayList<>();
                    List<TaskRepository.ActionResult> results=new ArrayList<>();
                    f.enqueueWorkspaceOperation(6,results::add,(snapshot,completion)->pending.add(completion));
                    f.mHandler.drain();
                    // The last framework observation arrives before the command acknowledgement.
                    f.drawControls();
                    check(!f.borderVisible && !f.navigationEnabled,"controls must wait for completion");
                    pending.get(0).onComplete(new TaskRepository.ActionResult(success,"finished"));
                    f.mHandler.drain();
                    check(results.size()==1 && results.get(0).success()==success,"preserve operation result");
                    check(f.refreshes==1 && f.borderVisible && f.navigationEnabled,
                            "completion left controls hidden and disabled until another window event");
                }
                Fixture stopped=new Fixture();
                List<TaskRepository.ActionCallback> pending=new ArrayList<>();
                stopped.enqueueWorkspaceOperation(6,null,(snapshot,completion)->pending.add(completion));
                stopped.mHandler.drain();
                stopped.mGeneration++;
                pending.get(0).onComplete(new TaskRepository.ActionResult(true,"late"));
                stopped.mHandler.drain();
                check(stopped.refreshes==0,"old session completion must not refresh its replacement");
            }
            """);
    }
}
