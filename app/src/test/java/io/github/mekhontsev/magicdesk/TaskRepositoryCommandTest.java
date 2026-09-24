package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

public final class TaskRepositoryCommandTest {
    @Test
    public void boundsUseTypedServiceWithSnapshotAndFailureDelivery() throws Exception {
        RuntimeSourceFixture.verify(RuntimeSourceFixture.methods("TaskRepository",
                "resizeTaskBounds", "isUsableTask", "hasExplicitBounds", "usefulMessage") + """
            static class Rect {
                int left, top, right, bottom;
                Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                Rect(Rect r) { this(r.left, r.top, r.right, r.bottom); }
            }
            static class TaskEntry { int taskId=42, rootTaskId=42, displayId=3; }
            interface ActionCallback { void complete(boolean success, String message); }
            static void complete(ActionCallback cb, boolean ok, String message) { cb.complete(ok, message); }
            static class TaskCommandQueue {
                static Runnable pending;
                static void execute(Runnable command) { pending=command; }
                static void drain() { Runnable command=pending; pending=null; command.run(); }
            }
            static class ShellAccess {
                static Rect received;
                static boolean fail;
                static void resizeTaskBounds(int display, int task, Rect bounds) throws IOException {
                    check(display==3 && task==42, "typed task identity");
                    if (fail) throw new IOException("service unavailable");
                    received=bounds;
                }
            }
            public static void verify() {
                TaskEntry task=new TaskEntry();
                Rect bounds=new Rect(10,20,810,620);
                int[] calls={0};
                resizeTaskBounds(task,bounds,(ok,message)-> { check(ok,"successful callback"); calls[0]++; });
                check(calls[0]==0,"queued completion");
                bounds.left=99;
                TaskCommandQueue.drain();
                check(calls[0]==1 && ShellAccess.received.left==10,"defensive bounds copy");
                ShellAccess.fail=true;
                resizeTaskBounds(task,bounds,(ok,message)-> {
                    check(!ok && message.contains("service unavailable"),"failure preserved"); calls[0]++;
                });
                TaskCommandQueue.drain();
                resizeTaskBounds(task,new Rect(10,20,10,620),(ok,message)-> {
                    check(!ok,"empty rectangle rejected"); calls[0]++;
                });
                resizeTaskBounds(null,bounds,(ok,message)-> { check(!ok,"missing task rejected"); calls[0]++; });
                check(calls[0]==4 && TaskCommandQueue.pending==null,"invalid bounds never dispatched");
            }
            """);
    }

    @Test
    public void taskEntryRecognizesBoundedFreeformState() {
        assertTrue(task("freeform", rect(10, 20, 810, 620))
                .isBoundedFreeform());
        assertFalse(task("fullscreen", rect(10, 20, 810, 620))
                .isBoundedFreeform());
        assertFalse(task("freeform", rect(10, 20, 10, 620))
                .isBoundedFreeform());
    }

    private static TaskRepository.TaskEntry task(
            final String windowingMode,
            final Rect bounds) {
        final TaskRepository.TaskEntry task = new TaskRepository.TaskEntry(
                1,
                2,
                3,
                "com.example",
                "com.example/.MainActivity",
                "com.example/.MainActivity",
                windowingMode,
                null,
                false,
                true,
                true);
        // android.jar does not implement Rect's copy constructor in JVM tests.
        task.bounds.left = bounds.left;
        task.bounds.top = bounds.top;
        task.bounds.right = bounds.right;
        task.bounds.bottom = bounds.bottom;
        return task;
    }

    private static Rect rect(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        // android.jar constructors are stubs in local JVM tests.
        final Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return bounds;
    }
}
