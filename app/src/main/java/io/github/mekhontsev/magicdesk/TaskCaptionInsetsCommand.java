package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;

/**
 * Controls whether a task receives the desktop caption inset.
 *
 * <p>The framework compatibility layer selects the native operation or the
 * source-based Android 15 fallback.</p>
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskCaptionInsetsCommand {
    private TaskCaptionInsetsCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 3) {
            System.err.println(
                    "usage: TaskCaptionInsetsCommand <display-id> <task-id> <exclude|include>");
            System.exit(64);
            return;
        }

        try {
            final int displayId = parseInt(args[0], "display id");
            final int taskId = parseInt(args[1], "task id");
            final boolean exclude;
            if ("exclude".equals(args[2])) {
                exclude = true;
            } else if ("include".equals(args[2])) {
                exclude = false;
            } else {
                throw new IllegalArgumentException("invalid caption inset operation");
            }
            final boolean applied = setCaptionInsetExcluded(
                    displayId, taskId, exclude);
            System.out.println("task-caption-inset="
                    + (applied
                            ? (exclude ? "excluded" : "included")
                            : "unsupported")
                    + " task=" + taskId + " display=" + displayId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("caption inset update failed: " + cause);
            System.exit(1);
        }
    }

    private static boolean setCaptionInsetExcluded(
            final int displayId,
            final int taskId,
            final boolean exclude) throws ReflectiveOperationException {
        final Object service = HiddenTaskApi.getService();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();

        final boolean applied = addCaptionInsetOperation(
                transaction, taskToken, exclude);
        if (!applied) {
            System.out.println("caption-inset-strategy="
                    + FrameworkRuntime.current()
                            .capabilities().captionStrategy());
            return false;
        }
        final int requestedTypes = FrameworkRuntime.current()
                .windowingCompat()
                .lastExcludeInsetsTypes(transaction);
        System.out.println("caption-inset-types=" + requestedTypes);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, transaction);
        return true;
    }

    static boolean addCaptionInsetOperation(
            final Object transaction,
            final Object taskToken,
            final boolean exclude)
            throws ReflectiveOperationException {
        return FrameworkRuntime.current().windowingCompat()
                .addCaptionExclusion(
                transaction, taskToken, exclude, getCaptionBarType());
    }

    static int getCaptionBarType() {
        return FrameworkRuntime.current().windowingCompat().captionBarType();
    }

    private static int parseInt(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }
}
