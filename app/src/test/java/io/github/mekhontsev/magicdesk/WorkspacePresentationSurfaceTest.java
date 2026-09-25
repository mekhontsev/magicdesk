package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class WorkspacePresentationSurfaceTest {
    @Test
    public void presentationCommitsConcealmentThroughThePlaneOwner() throws Exception {
        RuntimeSourceFixture.verify("""
                static final int WINDOWING_MODE_FREEFORM = 5;
                static final Object mService = new Object();
                static final StringBuilder calls = new StringBuilder();
                static class FrameworkWindowCommitBarrier {
                    static boolean available = true;
                    static void awaitSystemTransitions() throws ReflectiveOperationException {
                        calls.append("settle;");
                        if (!available) throw new ReflectiveOperationException("barrier unavailable");
                    }
                }
                static class DesktopWorkspaceCommand {
                    int displayId = 0, targetTaskId = 42;
                    boolean conceal;
                    boolean concealsFullscreenPlanes() { return conceal; }
                }
                static class ShellFullscreenTaskArea {
                    enum FocusResult { NOT_HANDLED, DESKTOP_FOREGROUND, WORKSPACE_FOREGROUND, FULLSCREEN_FOREGROUND }
                    FocusResult result = FocusResult.WORKSPACE_FOREGROUND;
                    boolean concealAccepted = true;
                    FocusResult focusStack(Object service, int display, int[] order) {
                        check(display == 0 && order[order.length - 1] == 42,
                                "workspace plan changed");
                        calls.append("order;");
                        return result;
                    }
                    boolean concealForShowDesktop(int display) {
                        calls.append("conceal;");
                        return concealAccepted;
                    }
                }
                static class HiddenTaskApi {
                    static Object requireTask(Object service, int display, int task) {
                        throw new AssertionError("owned presentation used raw task access");
                    }
                    static int getTaskWindowingMode(Object task) { return 1; }
                }
                static class TaskWindowingCommand {
                    static void focusTasks(Object service, int display, int[] tasks) {
                        throw new AssertionError("owned presentation submitted twice");
                    }
                    static void focusTasksWithSurfaceCommit(Object service, int display, int[] tasks) {
                        focusTasks(service, display, tasks);
                    }
                }
                final ShellFullscreenTaskArea mFullscreenTaskArea = new ShellFullscreenTaskArea();
                public static void verify() throws Exception {
                    Fixture fixture = new Fixture();
                    DesktopWorkspaceCommand command = new DesktopWorkspaceCommand();
                    command.conceal = true;
                    fixture.applyPhysicalOrder(command, new int[]{10, 42});
                    check(calls.toString().equals("order;settle;conceal;"),
                            "presentation concealed before native transitions settled");

                    calls.setLength(0);
                    command.conceal = false;
                    fixture.applyPhysicalOrder(command, new int[]{42});
                    check(calls.toString().equals("order;"), "selection concealed planes");

                    calls.setLength(0);
                    fixture.mFullscreenTaskArea.result = ShellFullscreenTaskArea.FocusResult.DESKTOP_FOREGROUND;
                    fixture.applyPhysicalOrder(command, new int[]{10, 42});
                    check(calls.toString().equals("order;settle;conceal;"),
                            "demotion to HOME left fullscreen surfaces visible");

                    calls.setLength(0);
                    fixture.mFullscreenTaskArea.result = ShellFullscreenTaskArea.FocusResult.FULLSCREEN_FOREGROUND;
                    fixture.applyPhysicalOrder(command, new int[]{42});
                    check(calls.toString().equals("order;"), "fullscreen successor was concealed");

                    command.conceal = true;
                    fixture.mFullscreenTaskArea.concealAccepted = false;
                    try {
                        fixture.applyPhysicalOrder(command, new int[]{42});
                        throw new AssertionError("failed concealment acknowledged success");
                    } catch (IllegalStateException expected) {
                        check(expected.getMessage().contains("could not be concealed"),
                                "concealment failure lost");
                    }

                    calls.setLength(0);
                    FrameworkWindowCommitBarrier.available = false;
                    try {
                        fixture.applyPhysicalOrder(command, new int[]{42});
                        throw new AssertionError("failed barrier acknowledged success");
                    } catch (ReflectiveOperationException expected) {
                        check(calls.toString().equals("order;settle;"),
                                "concealment proceeded after failed barrier");
                    }
                }
                """ + RuntimeSourceFixture.methods(
                "ShellDesktopWorkspaceCoordinator", "applyPhysicalOrder"));
    }
}
