package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Clears a caption source retained by an application after leaving freeform mode. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class TaskCaptionInsetsRefresher {
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final long DUMP_TIMEOUT_MILLIS = 3_000L;
    private static final int DUMP_LIMIT_BYTES = 1024 * 1024;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private TaskCaptionInsetsRefresher() {
    }

    static int captureCaptionSourceId(final int taskId) {
        final TaskLocalInsetsSourceParser.CaptionSource source =
                captureCaptionSource(taskId);
        return source == null
                ? TaskLocalInsetsSourceParser.NO_SOURCE_ID
                : source.sourceId;
    }

    static TaskLocalInsetsSourceParser.CaptionSource captureCaptionSource(
            final int taskId) {
        return captureCaptionSources(Collections.singleton(taskId)).get(
                Integer.valueOf(taskId));
    }

    /** Captures several task-local sources from one window dump. */
    static Map<Integer, Integer> captureCaptionSourceIds(
            final Set<Integer> taskIds) {
        final Map<Integer, Integer> result = new HashMap<>();
        for (final Map.Entry<Integer, TaskLocalInsetsSourceParser.CaptionSource> entry
                : captureCaptionSources(taskIds).entrySet()) {
            result.put(entry.getKey(), Integer.valueOf(entry.getValue().sourceId));
        }
        return result;
    }

    private static Map<Integer, TaskLocalInsetsSourceParser.CaptionSource>
            captureCaptionSources(final Set<Integer> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            final Process process = new ProcessBuilder(DUMPSYS, "window")
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                    process, DUMP_TIMEOUT_MILLIS, DUMP_LIMIT_BYTES);
            if (result.exitCode != 0) {
                System.err.println("caption source capture failed: dumpsys exit="
                        + result.exitCode);
                return Collections.emptyMap();
            }
            final Map<Integer, TaskLocalInsetsSourceParser.CaptionSource> sources =
                    new HashMap<>();
            for (final Integer taskId : taskIds) {
                final TaskLocalInsetsSourceParser.CaptionSource source =
                        TaskLocalInsetsSourceParser.findCaptionSource(
                                result.output, taskId.intValue());
                if (source != null) {
                    sources.put(taskId, source);
                }
            }
            if (result.truncated && sources.size() < taskIds.size()) {
                System.err.println(
                        "caption source capture incomplete: window dump truncated");
            }
            return sources;
        } catch (IOException e) {
            System.err.println("caption source capture failed: " + e.getMessage());
            return Collections.emptyMap();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("caption source capture interrupted");
            return Collections.emptyMap();
        }
    }

    static boolean shouldRefreshAfterWindowingModeChange(
            final int previousMode,
            final int currentMode,
            final int sourceId) {
        return previousMode == WINDOWING_MODE_FREEFORM
                && currentMode == WINDOWING_MODE_FULLSCREEN
                && sourceId != TaskLocalInsetsSourceParser.NO_SOURCE_ID;
    }

    static void refreshTask(
            final Object service,
            final int displayId,
            final int taskId,
            final int sourceId) throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        refresh(service, transactionClass, tokenClass, taskToken, sourceId);
    }

    static void refresh(final Object service,
            final Class<?> transactionClass,
            final Class<?> tokenClass,
            final Object taskToken,
            final int sourceId) throws ReflectiveOperationException {
        if (sourceId == TaskLocalInsetsSourceParser.NO_SOURCE_ID) {
            return;
        }
        final int captionType = TaskCaptionInsetsCommand.getCaptionBarType();
        final IBinder owner = new Binder();

        final Object add = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod(
                "addInsetsSource", tokenClass, IBinder.class,
                Integer.TYPE, Integer.TYPE, Rect.class, Rect[].class,
                Integer.TYPE)
                .invoke(add, taskToken, owner, Integer.valueOf(0),
                        Integer.valueOf(captionType), new Rect(), null,
                        Integer.valueOf(0));
        setLastProviderId(transactionClass, add, sourceId);
        SyncWindowContainerTransaction.apply(service, transactionClass, add);

        final Object remove = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod(
                "removeInsetsSource", tokenClass, IBinder.class,
                Integer.TYPE, Integer.TYPE)
                .invoke(remove, taskToken, owner, Integer.valueOf(0),
                        Integer.valueOf(captionType));
        setLastProviderId(transactionClass, remove, sourceId);
        SyncWindowContainerTransaction.apply(service, transactionClass, remove);
    }

    private static void setLastProviderId(final Class<?> transactionClass,
            final Object transaction, final int sourceId)
            throws ReflectiveOperationException {
        final List<?> operations = (List<?>) transactionClass
                .getMethod("getHierarchyOps").invoke(transaction);
        final Object operation = operations.get(operations.size() - 1);
        final Object provider = operation.getClass()
                .getMethod("getInsetsFrameProvider").invoke(operation);
        final Field id = provider.getClass().getDeclaredField("mId");
        id.setAccessible(true);
        id.setInt(provider, sourceId);
    }
}
