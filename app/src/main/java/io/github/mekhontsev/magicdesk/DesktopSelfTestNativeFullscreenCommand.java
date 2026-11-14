package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Reproduces WMShell's native fullscreen workspace exit on a test fixture. */
public final class DesktopSelfTestNativeFullscreenCommand {
    private DesktopSelfTestNativeFullscreenCommand() {}

    static boolean isFixtureBesideDesktop(
            final FrameworkTaskSnapshot task, final FrameworkTaskSnapshot host) {
        return task != null && host != null
                && host.activityType != 0
                && host.activityType == DesktopHostComponents.hostActivityType(host.componentName)
                && host.windowingMode == FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN
                && DesktopSelfTestComponents.isFixtureComponent(task.componentName)
                && task.windowingMode == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM
                && task.rootTaskId == task.taskId
                && task.displayAreaFeatureId == host.displayAreaFeatureId;
    }

    public static void main(final String[] args) {
        try {
            if (args.length != 3) {
                throw new IllegalArgumentException("expected display, fixture and desktop host task IDs");
            }
            final int displayId = Integer.parseInt(args[0]);
            final int taskId = Integer.parseInt(args[1]);
            final int homeTaskId = Integer.parseInt(args[2]);
            final Object service = HiddenTaskApi.getService();
            final FrameworkTaskSnapshot task = FrameworkTaskSnapshotSource.findTask(
                    service, displayId, taskId);
            final FrameworkTaskSnapshot home = FrameworkTaskSnapshotSource.findTask(
                    service, displayId, homeTaskId);
            if (!isFixtureBesideDesktop(task, home)) {
                throw new IllegalStateException("expected a freeform fixture beside its desktop host");
            }
            final FrameworkWindowingApi windowing = FrameworkRuntime.current().windowing();
            final Object transaction = windowing.newTransaction();
            final Object token = HiddenTaskApi.getTaskToken(task.task);
            windowing.setWindowingMode(transaction, token, 1);
            windowing.setBounds(transaction, token, new Rect());
            // DesktopTasksController.moveToFullscreenWithAnimation puts HOME
            // immediately below the exiting task, covering its freeform peers.
            windowing.reorder(transaction, HiddenTaskApi.requireRootTaskToken(
                    service, displayId, home.rootTaskId), true);
            windowing.reorder(transaction, token, true);
            ShellWindowTransitionExecutor.startForShellAdoption(displayId,
                    ShellWindowTransitionExecutor.SystemTransition.CHANGE,
                    windowing.transactionClass(), transaction, "self-test-native-fullscreen");
            TaskFullscreenTransitionCommand.awaitFullscreen(service, displayId, taskId);
            System.out.println("native-fullscreen task=" + taskId + " home=" + home.rootTaskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("native fullscreen test transition failed: " + error);
            System.exit(1);
        }
    }
}
