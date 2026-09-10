package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/** Per-attempt evidence captured before failed HOME startup releases its state. */
final class DesktopHostLaunchDiagnostics {
    private static final int TASK_LIMIT = 32;
    private static final int REPORTED_TASK_LIMIT = 4;
    private static final int DETAIL_LIMIT = 1600;

    private final long mStartedAt = SystemClock.elapsedRealtime();
    private final int mUid = Process.myUid();
    private final int mCallerUid = Binder.getCallingUid();
    private final int mDisplayId;
    private final String mComponent;
    String stage = "remove-stale-hosts";
    Integer startResult;
    int matchedTaskId = -1;
    Integer matchedActivityType;

    DesktopHostLaunchDiagnostics(final int displayId, final String component) {
        mDisplayId = displayId;
        mComponent = component;
    }

    void recordStartResult(final int result) {
        startResult = result;
        stage = result < 0 ? "start-rejected" : "await-task";
    }

    IllegalStateException failure(final Object service, final Throwable cause) {
        final StringBuilder detail = new StringBuilder("HOME launch: stage=")
                .append(stage).append(" uid=").append(mUid)
                .append(" binderCallerUid=").append(mCallerUid)
                .append(" targetDisplay=").append(mDisplayId)
                .append(" component=").append(singleLine(mComponent, 140))
                .append(" requestedType=2 requestedMode=1 startResult=")
                .append(startResult == null ? "not-returned" : startResult)
                .append(" matchedTask=").append(matchedTaskId)
                .append(" matchedType=").append(matchedActivityType == null
                        ? "unknown" : matchedActivityType)
                .append(" elapsedMs=").append(SystemClock.elapsedRealtime() - mStartedAt)
                .append(" cause=").append(singleLine(
                        TaskDisplayAreaLaunchCommand.causeChain(cause), 220));
        // Only failures take this one-shot typed sample. No retry or state mutation
        // is allowed here; unknown evidence must not replace the original failure.
        try {
            appendTasks(detail, FrameworkTaskSnapshotSource.readWindowState(
                    service, Display.INVALID_DISPLAY, TASK_LIMIT));
        } catch (ReflectiveOperationException | RuntimeException error) {
            detail.append(" tasks=unavailable:")
                    .append(singleLine(error.getClass().getSimpleName(), 80));
        }
        final String message = detail.length() <= DETAIL_LIMIT ? detail.toString()
                : BoundedText.prefix(detail, DETAIL_LIMIT - 15) + " truncated=true";
        return new IllegalStateException(message, cause);
    }

    private void appendTasks(final StringBuilder detail, final List<FrameworkTaskSnapshot> tasks) {
        final List<FrameworkTaskSnapshot> relevant = new ArrayList<>();
        // Keep the returned task first, then same-package tasks (possibly on a
        // different display), then competing HOME tasks. Never dump app contents.
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task.taskId == matchedTaskId) relevant.add(task);
        }
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task.taskId != matchedTaskId
                    && (BuildConfig.APPLICATION_ID.equals(task.packageName)
                            || BuildConfig.APPLICATION_ID.equals(task.topPackage))) relevant.add(task);
        }
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task.isHome() && !relevant.contains(task)) relevant.add(task);
        }
        detail.append(" sampleScope=all-displays sampleLimit=").append(TASK_LIMIT)
                .append(" sampled=").append(tasks.size())
                .append(" relevant=").append(relevant.size())
                .append(" shown=").append(Math.min(REPORTED_TASK_LIMIT, relevant.size()))
                .append(" tasks=[");
        for (int i = 0; i < Math.min(REPORTED_TASK_LIMIT, relevant.size()); i++) {
            final FrameworkTaskSnapshot task = relevant.get(i);
            if (i > 0) detail.append(';');
            detail.append("id=").append(task.taskId).append(" user=").append(task.userId)
                    .append(" display=").append(task.displayId).append(" root=").append(task.rootTaskId)
                    .append(" area=").append(task.displayAreaFeatureId)
                    .append(" type=").append(task.activityType).append(" mode=").append(task.windowingMode)
                    .append(" base=").append(singleLine(task.componentName, 100))
                    .append(" top=").append(singleLine(task.topActivityName, 100))
                    .append(" visible=").append(task.visible).append(" focused=").append(task.focused);
        }
        detail.append(']');
    }

    private static String singleLine(final String value, final int limit) {
        return BoundedText.prefix(value == null ? "unknown"
                : value.replace('\n', ' ').replace('\r', ' ').replace('\0', ' '), limit);
    }
}
