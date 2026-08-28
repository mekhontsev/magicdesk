package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
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
        refresh(service, taskToken, sourceId);
    }

    static void refresh(
            final Object service,
            final Object taskToken,
            final int sourceId) throws ReflectiveOperationException {
        if (sourceId == TaskLocalInsetsSourceParser.NO_SOURCE_ID) {
            return;
        }
        final int captionType = TaskCaptionInsetsCommand.getCaptionBarType();
        final IBinder owner = new Binder();
        final FrameworkRuntime framework = FrameworkRuntime.current();
        final FrameworkWindowingApi windowing = framework.windowing();
        final Class<?> transactionClass = windowing.transactionClass();

        final Object add = windowing.newTransaction();
        framework.windowingCompat().addEmptyCaptionSource(
                add, taskToken, owner, captionType, new Rect(), sourceId);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, add);

        final Object remove = windowing.newTransaction();
        framework.windowingCompat().removeCaptionSource(
                remove, taskToken, owner, captionType, sourceId);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, remove);
    }
}
