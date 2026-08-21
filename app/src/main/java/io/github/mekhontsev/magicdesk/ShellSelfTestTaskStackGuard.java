package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Captures task state synchronously from TaskStackListener callbacks. */
final class ShellSelfTestTaskStackGuard {
    private static final int ACTIVITY_TYPE_HOME = 2;

    private final Object mService;

    private SelfTestTaskStackInvariantAnalyzer mAnalyzer;
    private String mError = "";

    ShellSelfTestTaskStackGuard(final Object service) {
        mService = service;
    }

    synchronized void start(
            final int displayId,
            final int hostTaskId,
            final String stage) {
        mAnalyzer = new SelfTestTaskStackInvariantAnalyzer(
                displayId, hostTaskId, SystemClock.uptimeMillis());
        mError = "";
        try {
            mAnalyzer.begin(stage, capture());
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    synchronized void stage(final String stage) {
        if (mAnalyzer == null || !mError.isEmpty()) {
            return;
        }
        try {
            mAnalyzer.changeStage(stage, capture());
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    synchronized void sample(final String reason) {
        if (mAnalyzer == null || !mError.isEmpty()) {
            return;
        }
        try {
            mAnalyzer.sample(reason, capture(), true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    synchronized SelfTestTaskStackReport stop() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = mAnalyzer;
        mAnalyzer = null;
        if (analyzer == null) {
            return SelfTestTaskStackReport.unavailable(
                    "task-stack guard was not active");
        }
        if (!mError.isEmpty()) {
            return SelfTestTaskStackReport.unavailable(mError);
        }
        try {
            return analyzer.finish(capture());
        } catch (ReflectiveOperationException | RuntimeException error) {
            return SelfTestTaskStackReport.unavailable(usefulMessage(error));
        } finally {
            mError = "";
        }
    }

    synchronized void close() {
        mAnalyzer = null;
        mError = "";
    }

    private SelfTestTaskStackInvariantAnalyzer.Snapshot capture()
            throws ReflectiveOperationException {
        final List<SelfTestTaskStackInvariantAnalyzer.TaskState> states =
                new ArrayList<>();
        boolean visibilityKnown = true;
        for (final Object task : HiddenTaskApi.getAllTasks(mService)) {
            final int taskId = HiddenTaskApi.getIntField(task, "taskId");
            final int displayId = HiddenTaskApi.getTaskDisplayId(task);
            final int windowingMode = HiddenTaskApi
                    .getWindowConfigurationValue(task, "getWindowingMode");
            final int activityType = HiddenTaskApi
                    .getWindowConfigurationValue(task, "getActivityType");
            final int displayAreaFeatureId = getDisplayAreaFeatureId(task);
            final ComponentName component = HiddenTaskApi.getTaskComponent(task);
            final boolean fixture = component != null
                    && DesktopSelfTestComponents.isFixtureComponent(
                            component.flattenToString());
            boolean taskVisibilityKnown = true;
            boolean visible = false;
            try {
                visible = HiddenTaskApi.getBooleanField(task, "isVisible");
            } catch (ReflectiveOperationException error) {
                taskVisibilityKnown = false;
                visibilityKnown = false;
            }
            states.add(new SelfTestTaskStackInvariantAnalyzer.TaskState(
                    taskId,
                    displayId,
                    windowingMode,
                    visible,
                    taskVisibilityKnown,
                    fixture,
                    activityType == ACTIVITY_TYPE_HOME,
                    displayAreaFeatureId));
        }
        return new SelfTestTaskStackInvariantAnalyzer.Snapshot(
                SystemClock.uptimeMillis(), states, visibilityKnown);
    }

    private static int getDisplayAreaFeatureId(final Object task) {
        try {
            return HiddenTaskApi.getIntField(task, "displayAreaFeatureId");
        } catch (ReflectiveOperationException | RuntimeException error) {
            return SelfTestTaskStackInvariantAnalyzer
                    .DISPLAY_AREA_FEATURE_UNKNOWN;
        }
    }

    private void fail(final Throwable error) {
        mError = usefulMessage(error);
    }

    private static String usefulMessage(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getSimpleName() : message;
    }
}
