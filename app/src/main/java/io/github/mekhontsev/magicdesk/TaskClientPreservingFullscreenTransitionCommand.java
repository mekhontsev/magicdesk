package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

/**
 * Same-display fullscreen transition that preserves the current Activity instance.
 *
 * <p>The application's own insets request updates its client. This command does
 * not wait for the first fullscreen frame because repeating a slow transition
 * can discard the active video fullscreen session.</p>
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskClientPreservingFullscreenTransitionCommand {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private TaskClientPreservingFullscreenTransitionCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 2) {
            System.err.println("usage: TaskClientPreservingFullscreenTransitionCommand "
                    + "<display-id> <task-id>");
            System.exit(64);
            return;
        }

        try {
            final int displayId = parseInt(args[0], "display id");
            final int taskId = parseInt(args[1], "task id");
            submitFullscreen(displayId, taskId);
            System.out.println("task-fullscreen-client-preserved-submitted=" + taskId
                    + " display=" + displayId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("fullscreen transition failed: " + cause);
            System.exit(1);
        }
    }

    private static void submitFullscreen(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        final Object service = HiddenTaskApi.getService();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        windowing.setWindowingMode(
                transaction, taskToken, WINDOWING_MODE_FULLSCREEN);
        windowing.setBounds(transaction, taskToken, new Rect());
        windowing.reorder(transaction, taskToken, true);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction, taskToken, true);
        /*
         * The system transition accepts the transaction even when another system
         * transition is still running. Do not wait for the resulting windowing
         * mode here: a slow first frame can keep Nubia's launch transition open
         * indefinitely, while retrying would enqueue the same relaunch twice.
         * DesktopTaskController observes the task and completes its local state
         * once WindowManager eventually applies this transaction.
         */
        ShellWindowTransitionExecutor.startForShellAdoption(
                displayId,
                ShellWindowTransitionExecutor.SystemTransition.CHANGE,
                transactionClass,
                transaction,
                "client-preserving-fullscreen");
    }

    private static int parseInt(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }

}
