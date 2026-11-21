package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestFreeformProbeTest {
    @Test public void successfulLaunchAloneDoesNotProveFreeformSupport() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect {
                    final int left, top, right, bottom;
                    Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                    boolean isEmpty() { return left >= right || top >= bottom; }
                    boolean contains(Rect r) { return left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom; }
                    public boolean equals(Object other) {
                        return other instanceof Rect r && left == r.left && top == r.top && right == r.right && bottom == r.bottom;
                    }
                }
                static class TaskRepository {
                    static class TaskEntry {
                        int displayId = 7;
                        boolean visible = true;
                        String windowingMode = "freeform";
                        Rect bounds = new Rect(100, 100, 700, 500);
                    }
                }
                public static void verify() throws Exception {
                    Rect screen = new Rect(0, 0, 1920, 1080);
                    TaskRepository.TaskEntry task = new TaskRepository.TaskEntry();
                    check(isVisibleFreeform(task, 7, screen), "valid independent window rejected");
                    task.windowingMode = "fullscreen";
                    check(!isVisibleFreeform(task, 7, screen), "firmware fullscreen fallback passed");
                    task.windowingMode = "freeform";
                    task.visible = false;
                    check(!isVisibleFreeform(task, 7, screen), "hidden task passed");
                    task.visible = true;
                    check(!isVisibleFreeform(task, 0, screen), "wrong display passed");
                    task.bounds = screen;
                    check(!isVisibleFreeform(task, 7, screen), "whole-screen geometry passed");
                    task.bounds = new Rect(20, 152, 1300, 2200);
                    check(!isVisibleFreeform(task, 7, screen), "off-screen firmware geometry passed");
                    check(parseTaskId("noise\\ntask-display-area-launch=123\\n") == 123, "task identity lost");
                    for (String output : new String[]{"", "task-display-area-launch=-1", "task-display-area-launch=bad"}) {
                        try { parseTaskId(output); throw new AssertionError("invalid launch accepted"); }
                        catch (IOException expected) { }
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopSelfTestFreeformProbe", "isVisibleFreeform", "parseTaskId"));
    }
}
