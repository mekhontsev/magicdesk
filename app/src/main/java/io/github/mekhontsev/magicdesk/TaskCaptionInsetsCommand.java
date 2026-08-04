package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Controls whether a task receives the desktop caption inset.
 *
 * <p>Android only exposes the IME variant of this WindowContainerTransaction operation,
 * so the caption type is selected on the generated hierarchy operation via reflection.</p>
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
            setCaptionInsetExcluded(displayId, taskId, exclude);
            System.out.println("task-caption-inset=" + (exclude ? "excluded" : "included")
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

    private static void setCaptionInsetExcluded(final int displayId, final int taskId,
            final boolean exclude) throws ReflectiveOperationException {
        final Object service = HiddenTaskApi.getService();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();

        addCaptionInsetOperation(
                transactionClass, transaction, tokenClass, taskToken, exclude);
        final List<?> hierarchyOps = (List<?>) transactionClass
                .getMethod("getHierarchyOps")
                .invoke(transaction);
        final Object hierarchyOp = hierarchyOps.get(hierarchyOps.size() - 1);
        final int requestedTypes = ((Integer) hierarchyOp.getClass()
                .getMethod("getExcludeInsetsTypes")
                .invoke(hierarchyOp)).intValue();
        System.out.println("caption-inset-types=" + requestedTypes);
        SyncWindowContainerTransaction.apply(service, transactionClass, transaction);
    }

    static void addCaptionInsetOperation(final Class<?> transactionClass,
            final Object transaction, final Class<?> tokenClass,
            final Object taskToken, final boolean exclude)
            throws ReflectiveOperationException {
        transactionClass.getMethod("setExcludeImeInsets", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.valueOf(exclude));
        if (!exclude) {
            return;
        }
        final List<?> hierarchyOps = (List<?>) transactionClass
                .getMethod("getHierarchyOps")
                .invoke(transaction);
        final Object hierarchyOp = hierarchyOps.get(hierarchyOps.size() - 1);
        final Field excludeInsetsTypes =
                hierarchyOp.getClass().getDeclaredField("mExcludeInsetsTypes");
        excludeInsetsTypes.setAccessible(true);
        excludeInsetsTypes.setInt(hierarchyOp, getCaptionBarType());
    }

    static int getCaptionBarType() throws ReflectiveOperationException {
        final Class<?> typeClass = Class.forName("android.view.WindowInsets$Type");
        return ((Integer) typeClass.getMethod("captionBar").invoke(null)).intValue();
    }

    private static int parseInt(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }
}
