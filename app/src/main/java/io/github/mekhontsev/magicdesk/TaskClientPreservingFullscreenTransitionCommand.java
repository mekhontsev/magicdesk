package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

/**
 * Same-display fullscreen transition that preserves the current Activity instance.
 *
 * <p>App-requested fullscreen must not pulse task density because recreating the
 * Activity would discard the active video fullscreen session.</p>
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
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(transaction, taskToken, Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, new Rect());
        transactionClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.TRUE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass, transaction, tokenClass, taskToken, true);
        /*
         * startNewTransition() accepts the transaction even when another system
         * transition is still running. Do not wait for the resulting windowing
         * mode here: a slow first frame can keep Nubia's launch transition open
         * indefinitely, while retrying would enqueue the same relaunch twice.
         * DesktopTaskController observes the task and completes its local state
         * once WindowManager eventually applies this transaction.
         */
        TaskFullscreenTransitionCommand.startTransition(transactionClass, transaction);
    }

    private static int parseInt(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }

}
