package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ConsoleTerminalActionsTest {
    @Test public void lateCallbacksCannotRestartWorkAfterWindowCloses() throws Exception {
        verify("""
                Actions actions=new Actions(); int[] calls={0};
                actions.execute(() -> calls[0]++);
                actions.mContentWorker.pending.run();
                check(calls[0]==1, "active window did not execute content action");
                actions.execute(() -> calls[0]++);
                actions.close();
                actions.mContentWorker.pending.run();
                actions.execute(() -> calls[0]++);
                check(calls[0]==1 && actions.mContentWorker.submissions==2,
                        "closed window accepted or ran late work");
                check(actions.mContentWorker.stopped, "window left content worker running");
                """);
    }

    @Test public void closeRacingWithSubmissionDoesNotLeakARejectedExecution() throws Exception {
        verify("""
                Actions actions=new Actions();
                actions.mContentWorker.beforeSubmit=actions::close;
                actions.execute(() -> { throw new AssertionError("late callback ran"); });
                Actions active=new Actions(); active.mContentWorker.stopped=true;
                boolean rejected=false;
                try { active.execute(() -> {}); }
                catch (java.util.concurrent.RejectedExecutionException expected) { rejected=true; }
                check(rejected, "unrelated executor failure was swallowed");
                """);
    }

    private static void verify(String body) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class Worker {
                    Runnable pending, beforeSubmit; int submissions; boolean stopped;
                    void execute(Runnable action) {
                        if(beforeSubmit!=null) beforeSubmit.run();
                        if(stopped) throw new java.util.concurrent.RejectedExecutionException();
                        submissions++; pending=action;
                    }
                    void shutdownNow() { stopped=true; }
                }
                static class Actions implements AutoCloseable {
                    final Worker mContentWorker=new Worker(); volatile boolean mClosed;
                """ + RuntimeSourceFixture.methods("ConsoleTerminalActions", "execute", "close")
                + "}\npublic static void verify() {\n" + body + "\n}");
    }
}
