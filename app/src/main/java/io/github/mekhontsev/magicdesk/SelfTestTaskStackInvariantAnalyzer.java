package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Evaluates event-driven task snapshots without depending on Android APIs. */
final class SelfTestTaskStackInvariantAnalyzer {
    static final int WINDOWING_MODE_FULLSCREEN = 1;
    static final int WINDOWING_MODE_FREEFORM = 5;

    private static final int MAX_STAGE_SAMPLES = 128;
    private static final int MAX_ANOMALIES = 32;
    private final int mDisplayId;
    private final int mHostTaskId;
    private final long mStartedAt;
    private final List<String> mAnomalies = new ArrayList<>();
    private final Set<String> mAnomalyKeys = new LinkedHashSet<>();
    private Stage mStage;
    private int mStageCount;
    private int mSampleCount;
    private int mEventCount;
    private int mDroppedSamples;

    SelfTestTaskStackInvariantAnalyzer(
            final int displayId,
            final int hostTaskId,
            final long startedAt) {
        mDisplayId = displayId;
        mHostTaskId = hostTaskId;
        mStartedAt = startedAt;
    }

    void begin(final String stage, final Snapshot snapshot) {
        if (mStage != null) {
            throw new IllegalStateException("task-stack guard already started");
        }
        mStage = new Stage(cleanStage(stage));
        mStageCount++;
        addSample("stage-start", snapshot, false);
    }

    void changeStage(final String stage, final Snapshot snapshot) {
        if (mStage == null) {
            begin(stage, snapshot);
            return;
        }
        addSample("stage-end", snapshot, false);
        analyzeStage(mStage);
        mStage = new Stage(cleanStage(stage));
        mStageCount++;
        addSample("stage-start", snapshot, false);
    }

    void sample(
            final String reason,
            final Snapshot snapshot,
            final boolean event) {
        if (mStage == null) {
            begin("WINDOW-STACK", snapshot);
            return;
        }
        addSample(reason, snapshot, event);
    }

    SelfTestTaskStackReport finish(final Snapshot snapshot) {
        if (mStage != null) {
            addSample("finish", snapshot, false);
            analyzeStage(mStage);
            mStage = null;
        }
        if (mDroppedSamples > 0) {
            addAnomaly("trace-overflow", "task-stack trace dropped "
                    + mDroppedSamples + " samples");
        }
        return new SelfTestTaskStackReport(
                true,
                mStageCount,
                mSampleCount,
                mEventCount,
                mDroppedSamples,
                mAnomalies.toArray(new String[0]),
                "");
    }

    private void addSample(
            final String reason,
            final Snapshot snapshot,
            final boolean event) {
        if (snapshot == null) {
            addAnomaly("missing-snapshot", stagePrefix()
                    + " task snapshot is unavailable");
            return;
        }
        mSampleCount++;
        if (event) {
            mEventCount++;
        }
        validateUniversal(reason, snapshot);
        if (mStage.samples.size() >= MAX_STAGE_SAMPLES) {
            mDroppedSamples++;
            return;
        }
        mStage.samples.add(new Sample(reason, snapshot));
    }

    private void validateUniversal(
            final String reason,
            final Snapshot snapshot) {
        final TaskState host = snapshot.find(mHostTaskId);
        if (host == null) {
            addAnomaly("host-missing:" + mStage.name,
                    formatSample(reason, snapshot)
                            + " desktop host is missing");
        } else if (host.displayId != mDisplayId
                || host.windowingMode != WINDOWING_MODE_FULLSCREEN) {
            addAnomaly("host-state:" + mStage.name + ':' + host.stateKey(),
                    formatSample(reason, snapshot)
                            + " desktop host=" + host.stateKey());
        }

        boolean visibleOnDesktop = false;
        for (final TaskState task : snapshot.tasks) {
            if (task.displayId == mDisplayId
                    && task.visibilityKnown && task.visible) {
                visibleOnDesktop = true;
            }
            if (task.fixture
                    && mDisplayId != 0
                    && task.displayId == 0
                    && task.windowingMode == WINDOWING_MODE_FREEFORM) {
                addAnomaly("phone-freeform:" + mStage.name + ':' + task.taskId,
                        formatSample(reason, snapshot)
                                + " fixture " + task.taskId
                                + " entered phone/freeform");
            }
            if (task.fixture
                    && task.displayId != 0
                    && task.displayId != mDisplayId) {
                addAnomaly("fixture-display:" + mStage.name + ':'
                                + task.taskId + ':' + task.displayId,
                        formatSample(reason, snapshot)
                                + " fixture " + task.taskId
                                + " entered display " + task.displayId);
            }
            if (task.taskId != mHostTaskId
                    && mDisplayId != 0
                    && task.home && task.displayId == mDisplayId
                    && task.visibilityKnown && task.visible) {
                addAnomaly("visible-home:" + mStage.name + ':' + task.taskId,
                        formatSample(reason, snapshot)
                                + " Home task " + task.taskId
                                + " became visible");
            }
        }
        if (snapshot.visibilityKnown && !visibleOnDesktop) {
            addAnomaly("visibility-gap:" + mStage.name,
                    formatSample(reason, snapshot)
                            + " no task is visible on the desktop display");
        }
    }

    private void analyzeStage(final Stage stage) {
        if (stage.samples.size() < 2) {
            return;
        }
        final Snapshot first = stage.samples.get(0).snapshot;
        final Snapshot last = stage.samples.get(
                stage.samples.size() - 1).snapshot;
        final Set<Integer> fixtureIds = new LinkedHashSet<>();
        collectFixtureIds(first, fixtureIds);
        collectFixtureIds(last, fixtureIds);
        for (final Sample sample : stage.samples) {
            collectFixtureIds(sample.snapshot, fixtureIds);
        }
        for (final Integer taskId : fixtureIds) {
            analyzeFixture(stage, taskId.intValue());
        }
        analyzeVisibility(stage, first, last);
    }

    private void analyzeFixture(final Stage stage, final int taskId) {
        final TaskState firstState =
                stage.samples.get(0).snapshot.find(taskId);
        final TaskState lastState = stage.samples.get(
                stage.samples.size() - 1).snapshot.find(taskId);
        final String first = stateKey(firstState);
        final String last = stateKey(lastState);
        boolean reachedFinal = first.equals(last);
        for (final Sample sample : stage.samples) {
            final TaskState currentState = sample.snapshot.find(taskId);
            final String current = stateKey(currentState);
            if (first.equals(last)) {
                if (!first.equals(current)) {
                    addTaskTransitionAnomaly(
                            stage, sample, taskId, first, last, current);
                    return;
                }
                continue;
            }
            if (first.equals(current) && !reachedFinal) {
                continue;
            }
            if (last.equals(current)) {
                reachedFinal = true;
                continue;
            }
            if (isHiddenPreparation(firstState, lastState, currentState)) {
                continue;
            }
            addTaskTransitionAnomaly(
                    stage, sample, taskId, first, last, current);
            return;
        }
    }

    private void addTaskTransitionAnomaly(
            final Stage stage,
            final Sample sample,
            final int taskId,
            final String first,
            final String last,
            final String current) {
        addAnomaly("task-transition:" + stage.name + ':' + taskId,
                formatSample(sample.reason, sample.snapshot)
                        + " task=" + taskId
                        + " expected=" + first + " -> " + last
                        + " observed=" + current);
    }

    private void analyzeVisibility(
            final Stage stage,
            final Snapshot first,
            final Snapshot last) {
        if (!allVisibilityKnown(stage)) {
            return;
        }
        final boolean stableDesktopBackground = hostVisible(first)
                && hostVisible(last)
                && !hasVisibleFullscreenFixture(first)
                && !hasVisibleFullscreenFixture(last);
        final boolean stableFullscreen = hasVisibleFullscreenFixture(first)
                && hasVisibleFullscreenFixture(last);
        for (final Sample sample : stage.samples) {
            if (stableDesktopBackground && !hostVisible(sample.snapshot)) {
                addAnomaly("host-visibility:" + stage.name,
                        formatSample(sample.reason, sample.snapshot)
                                + " desktop host became invisible"
                                + " during a windowed operation");
                return;
            }
            if (stableFullscreen
                    && !hasVisibleFullscreenFixture(sample.snapshot)) {
                addAnomaly("fullscreen-visibility:" + stage.name,
                        formatSample(sample.reason, sample.snapshot)
                                + " no fullscreen fixture is visible"
                                + " during fullscreen switching");
                return;
            }
        }
    }

    private boolean allVisibilityKnown(final Stage stage) {
        for (final Sample sample : stage.samples) {
            if (!sample.snapshot.visibilityKnown) {
                return false;
            }
        }
        return true;
    }

    private boolean hostVisible(final Snapshot snapshot) {
        final TaskState host = snapshot.find(mHostTaskId);
        return host != null && host.visibilityKnown && host.visible;
    }

    private boolean hasVisibleFullscreenFixture(final Snapshot snapshot) {
        for (final TaskState task : snapshot.tasks) {
            if (task.fixture
                    && task.displayId == mDisplayId
                    && task.windowingMode == WINDOWING_MODE_FULLSCREEN
                    && task.visibilityKnown
                    && task.visible) {
                return true;
            }
        }
        return false;
    }

    private static void collectFixtureIds(
            final Snapshot snapshot,
            final Set<Integer> destination) {
        for (final TaskState task : snapshot.tasks) {
            if (task.fixture) {
                destination.add(Integer.valueOf(task.taskId));
            }
        }
    }

    private static String stateKey(final TaskState state) {
        return state == null ? "absent" : state.stateKey();
    }

    private static boolean isHiddenPreparation(
            final TaskState first,
            final TaskState last,
            final TaskState current) {
        if (last == null
                || current == null
                || !current.visibilityKnown
                || current.visible) {
            return false;
        }
        if (first == null) {
            // Android may create a task in its parent's default mode before
            // applying the requested launch mode. It is safe only while the
            // task remains hidden on its final display.
            return current.displayId == last.displayId;
        }
        if (first.displayId == last.displayId
                && current.displayId == last.displayId
                && current.windowingMode == first.windowingMode) {
            // An application may reach the final mode before WMShell has
            // restored its decoration. Re-entering the source mode is safe
            // while hidden and gives the following transition a real mode
            // boundary without exposing that intermediate state.
            return true;
        }
        return first.displayId != last.displayId
                && current.displayId == first.displayId
                && current.windowingMode == last.windowingMode;
    }

    private String formatSample(
            final String reason,
            final Snapshot snapshot) {
        return "+" + Math.max(0L, snapshot.uptimeMillis - mStartedAt)
                + "ms " + stagePrefix() + '/' + cleanReason(reason)
                + " " + snapshot.summary(mHostTaskId);
    }

    private String stagePrefix() {
        return mStage == null ? "WINDOW-STACK" : mStage.name;
    }

    private void addAnomaly(final String key, final String detail) {
        if (!mAnomalyKeys.add(key) || mAnomalies.size() >= MAX_ANOMALIES) {
            return;
        }
        mAnomalies.add(detail);
    }

    private static String cleanStage(final String stage) {
        return stage == null || stage.isEmpty() ? "WINDOW-STACK" : stage;
    }

    private static String cleanReason(final String reason) {
        return reason == null || reason.isEmpty() ? "event" : reason;
    }

    static final class Snapshot {
        final long uptimeMillis;
        final List<TaskState> tasks;
        final boolean visibilityKnown;

        Snapshot(
                final long uptimeMillis,
                final List<TaskState> tasks,
                final boolean visibilityKnown) {
            this.uptimeMillis = uptimeMillis;
            this.tasks = Collections.unmodifiableList(
                    new ArrayList<>(tasks));
            this.visibilityKnown = visibilityKnown;
        }

        TaskState find(final int taskId) {
            for (final TaskState task : tasks) {
                if (task.taskId == taskId) {
                    return task;
                }
            }
            return null;
        }

        String summary(final int hostTaskId) {
            final StringBuilder output = new StringBuilder();
            for (final TaskState task : tasks) {
                if (task.taskId != hostTaskId && !task.fixture && !task.home) {
                    continue;
                }
                if (output.length() > 0) {
                    output.append(',');
                }
                output.append(task.taskId == hostTaskId ? "host" : task.taskId)
                        .append('=')
                        .append(task.stateKey())
                        .append(task.visibilityKnown
                                ? task.visible ? "/visible" : "/hidden"
                                : "/visibility-unknown");
            }
            return output.length() == 0 ? "tasks=none" : output.toString();
        }
    }

    static final class TaskState {
        final int taskId;
        final int displayId;
        final int windowingMode;
        final boolean visible;
        final boolean visibilityKnown;
        final boolean fixture;
        final boolean home;

        TaskState(
                final int taskId,
                final int displayId,
                final int windowingMode,
                final boolean visible,
                final boolean visibilityKnown,
                final boolean fixture,
                final boolean home) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.windowingMode = windowingMode;
            this.visible = visible;
            this.visibilityKnown = visibilityKnown;
            this.fixture = fixture;
            this.home = home;
        }

        String stateKey() {
            return "display" + displayId + "/mode" + windowingMode;
        }
    }

    private static final class Stage {
        final String name;
        final List<Sample> samples = new ArrayList<>();

        Stage(final String name) {
            this.name = name;
        }
    }

    private static final class Sample {
        final String reason;
        final Snapshot snapshot;

        Sample(final String reason, final Snapshot snapshot) {
            this.reason = reason;
            this.snapshot = snapshot;
        }
    }
}
