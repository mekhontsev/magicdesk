package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Reproduces WMShell's native fullscreen workspace exit on a test fixture. */
public final class DesktopSelfTestNativeFullscreenCommand {
    private DesktopSelfTestNativeFullscreenCommand() {}

    public static void main(final String[] args) {
        try {
            if (args.length != 3) {
                throw new IllegalArgumentException("expected display, fixture and HOME task IDs");
            }
            final int displayId = Integer.parseInt(args[0]);
            final int taskId = Integer.parseInt(args[1]);
            final int homeTaskId = Integer.parseInt(args[2]);
            final Object service = HiddenTaskApi.getService();
            final FrameworkTaskSnapshot task = FrameworkTaskSnapshotSource.findTask(
                    service, displayId, taskId);
            final FrameworkTaskSnapshot home = FrameworkTaskSnapshotSource.findTask(
                    service, displayId, homeTaskId);
            if (task == null || home == null || !home.isHome()
                    || !DesktopSelfTestComponents.isFixtureComponent(task.componentName)
                    || task.windowingMode != 5 || task.rootTaskId != taskId
                    || task.displayAreaFeatureId != home.displayAreaFeatureId) {
                throw new IllegalStateException("expected a freeform fixture beside its HOME");
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
