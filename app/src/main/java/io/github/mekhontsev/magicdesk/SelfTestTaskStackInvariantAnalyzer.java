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
    static final int DISPLAY_AREA_FEATURE_UNKNOWN = Integer.MIN_VALUE;

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
    private int mSharedFullscreenParentFeatureId =
            DISPLAY_AREA_FEATURE_UNKNOWN;
    private String mPendingVisibilityGapKey;
    private String mPendingVisibilityGapDetail;

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
        observeFullscreenTaskArea(snapshot);
        validateFullscreenTaskArea(reason, snapshot);
        validateUniversal(reason, snapshot);
        validateVisibilityContinuity(reason, snapshot, event);
        if (!mStage.samples.isEmpty()
                && mStage.samples.get(mStage.samples.size() - 1)
                        .snapshot.sameState(snapshot)) {
            return;
        }
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

        for (final TaskState task : snapshot.tasks) {
            if (task.fixture && task.displayId != mDisplayId
                    && task.windowingMode != WINDOWING_MODE_FULLSCREEN) {
                addAnomaly("fixture-display:" + mStage.name + ':'
                                + task.taskId + ':' + task.displayId,
                        formatSample(reason, snapshot)
                                + " fixture " + task.taskId
                                + " left selected display " + mDisplayId
                                + " for display " + task.displayId
                                + " in mode " + task.windowingMode);
            }
            if (task.taskId != mHostTaskId && !task.backstop
                    && task.home && task.displayId == mDisplayId
                    && task.visibilityKnown && task.visible) {
                if (!isHomeAboveDesktopContent(snapshot, task.taskId)) {
                    continue;
                }
                addAnomaly("visible-home:" + mStage.name + ':' + task.taskId,
                        formatSample(reason, snapshot)
                                + " Home task " + task.taskId
                                + " became visible");
            }
        }
    }

    private void validateVisibilityContinuity(
            final String reason,
            final Snapshot snapshot,
            final boolean event) {
        if (!snapshot.visibilityKnown) {
            return;
        }
        if (hasVisibleDesktopTask(snapshot)) {
            mPendingVisibilityGapKey = null;
            mPendingVisibilityGapDetail = null;
            return;
        }
        if (mPendingVisibilityGapKey == null) {
            final String key = "visibility-gap:" + mStage.name;
            final String detail = formatSample(reason, snapshot)
                    + " no task is visible on the desktop display";
            if (!event || !"task-front".equals(reason)) {
                addAnomaly(key, detail);
                return;
            }
            mPendingVisibilityGapKey = key;
            mPendingVisibilityGapDetail = detail;
        }
        // The remote onTaskMovedToFront callback can precede its matching
        // visibility update. Android coalesces onTaskStackChanged after that
        // event burst, so it is the first committed boundary at which this
        // specific gap is a real task-stack defect. Deferral can begin only
        // at task-front; a gap first seen in any other callback is immediately
        // invalid. Stage boundaries remain strict, without a delay or timeout.
        if (!event || "stack-changed".equals(reason)) {
            addAnomaly(
                    mPendingVisibilityGapKey,
                    mPendingVisibilityGapDetail);
        }
    }

    private boolean hasVisibleDesktopTask(final Snapshot snapshot) {
        for (final TaskState task : snapshot.tasks) {
            if (task.displayId == mDisplayId
                    && !task.backstop
                    && task.visibilityKnown
                    && task.visible) {
                return true;
            }
        }
        return false;
    }

    private boolean isHomeAboveDesktopContent(
            final Snapshot snapshot,
            final int homeTaskId) {
        // ActivityTaskManager returns running tasks top-first. Nubia keeps its
        // phone HOME task marked visible below the local desktop, so visibility
        // alone is not an error; crossing the desktop content in Z-order is.
        for (final TaskState task : snapshot.tasks) {
            if (task.displayId != mDisplayId
                    || !task.visibilityKnown
                    || !task.visible) {
                continue;
            }
            if (task.taskId == homeTaskId) {
                return true;
            }
            if (task.taskId == mHostTaskId || task.fixture || task.backstop) {
                return false;
            }
        }
        return false;
    }

    private void analyzeStage(final Stage stage) {
        if (stage.samples.isEmpty()) {
            return;
        }
        final Snapshot last = stage.samples.get(
                stage.samples.size() - 1).snapshot;
        analyzeFullscreenHierarchyContract(stage, last);
        analyzeFullscreenTaskAreaContinuity(stage, last);
        if (stage.samples.size() < 2) {
            return;
        }
        final Snapshot first = stage.samples.get(0).snapshot;
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

    private void analyzeFullscreenHierarchyContract(
            final Stage stage,
            final Snapshot last) {
        if ("WINDOW-015".equals(stage.name)) {
            analyzeFullscreenHierarchy(stage, last);
            return;
        }
        if (!isSharedFullscreenStage(stage.name)) {
            return;
        }
        analyzeFullscreenHierarchy(stage, last);
        if (isStableFullscreenSwitchStage(stage.name)) {
            analyzeFullscreenParentStability(stage);
        }
    }

    private void analyzeFullscreenHierarchy(
            final Stage stage,
            final Snapshot snapshot) {
        analyzeSharedFullscreenArea(stage, snapshot);
    }

    private void analyzeSharedFullscreenArea(
            final Stage stage,
            final Snapshot snapshot) {
        final List<TaskState> fullscreenTasks =
                desktopFullscreenFixtures(snapshot);
        if (fullscreenTasks.size() < 2
                || mSharedFullscreenParentFeatureId
                        == DISPLAY_AREA_FEATURE_UNKNOWN) {
            addAnomaly("fullscreen-shared-area:" + stage.name,
                    formatSample("stage-end", snapshot)
                            + " fullscreen peers did not enter one shared area");
            return;
        }
        int areaTaskCount = 0;
        for (final TaskState task : fullscreenTasks) {
            if (task.displayAreaFeatureId
                    == mSharedFullscreenParentFeatureId) {
                areaTaskCount++;
            }
        }
        final int expectedAreaTaskCount = fullscreenTasks.size();
        if (areaTaskCount < expectedAreaTaskCount) {
            addAnomaly("fullscreen-shared-members:" + stage.name,
                    formatSample("stage-end", snapshot)
                            + " expected at least " + expectedAreaTaskCount
                            + " fullscreen tasks in area="
                            + mSharedFullscreenParentFeatureId
                            + ", found=" + areaTaskCount);
        }
    }

    private void analyzeFullscreenParentStability(final Stage stage) {
        if (stage.samples.isEmpty()) {
            return;
        }
        final Snapshot first = stage.samples.get(0).snapshot;
        for (final TaskState expected : desktopFullscreenFixtures(first)) {
            for (final Sample sample : stage.samples) {
                final TaskState current = sample.snapshot.find(expected.taskId);
                if (current != null
                        && current.displayAreaFeatureId
                                != expected.displayAreaFeatureId) {
                    addAnomaly("fullscreen-parent-transition:"
                                    + stage.name + ':' + expected.taskId,
                            formatSample(sample.reason, sample.snapshot)
                                    + " task=" + expected.taskId
                                    + " parent changed "
                                    + expected.displayAreaFeatureId + " -> "
                                    + current.displayAreaFeatureId);
                    break;
                }
            }
        }
    }

    private List<TaskState> desktopFullscreenFixtures(
            final Snapshot snapshot) {
        final List<TaskState> tasks = new ArrayList<>();
        for (final TaskState task : snapshot.tasks) {
            if (isDesktopFullscreenFixture(task)) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    private static boolean isSharedFullscreenStage(final String stage) {
        return "WINDOW-020-PREPARE".equals(stage)
                || "WINDOW-020".equals(stage)
                || stage.startsWith("WINDOW-020-PEER-")
                || "WINDOW-020-RETURN".equals(stage);
    }

    private static boolean isStableFullscreenSwitchStage(
            final String stage) {
        return stage.startsWith("WINDOW-020-PEER-")
                || "WINDOW-020-RETURN".equals(stage);
    }

    private void observeFullscreenTaskArea(final Snapshot snapshot) {
        final TaskState host = snapshot.find(mHostTaskId);
        final int hostFeatureId = host == null
                ? DISPLAY_AREA_FEATURE_UNKNOWN : host.displayAreaFeatureId;
        for (int firstIndex = 0;
                firstIndex < snapshot.tasks.size(); firstIndex++) {
            final TaskState first = snapshot.tasks.get(firstIndex);
            if (!isDesktopFullscreenFixture(first)
                    || first.displayAreaFeatureId
                            == DISPLAY_AREA_FEATURE_UNKNOWN) {
                continue;
            }
            for (int secondIndex = firstIndex + 1;
                    secondIndex < snapshot.tasks.size(); secondIndex++) {
                final TaskState second = snapshot.tasks.get(secondIndex);
                if (isDesktopFullscreenFixture(second)
                        && second.displayAreaFeatureId
                                == first.displayAreaFeatureId) {
                    if (first.displayAreaFeatureId == hostFeatureId
                            && !hasSessionBackstop(
                                    snapshot, hostFeatureId)) {
                        continue;
                    }
                    mSharedFullscreenParentFeatureId =
                            first.displayAreaFeatureId;
                    return;
                }
            }
        }
    }

    private boolean hasSessionBackstop(
            final Snapshot snapshot,
            final int featureId) {
        if (featureId == DISPLAY_AREA_FEATURE_UNKNOWN) {
            return false;
        }
        for (final TaskState task : snapshot.tasks) {
            if (task.backstop
                    && task.displayId == mDisplayId
                    && task.displayAreaFeatureId == featureId) {
                return true;
            }
        }
        return false;
    }

    private void analyzeFullscreenTaskAreaContinuity(
            final Stage stage,
            final Snapshot snapshot) {
        if (!("FULLSCREEN-LIFECYCLE-001".equals(stage.name)
                || "FULLSCREEN-LIFECYCLE-003".equals(stage.name))
                || mSharedFullscreenParentFeatureId
                        == DISPLAY_AREA_FEATURE_UNKNOWN) {
            return;
        }
        TaskState survivor = null;
        for (final TaskState task : snapshot.tasks) {
            if (!isDesktopFullscreenFixture(task)) {
                continue;
            }
            if (survivor != null) {
                return;
            }
            survivor = task;
        }
        if (survivor == null
                || survivor.displayAreaFeatureId
                        == mSharedFullscreenParentFeatureId) {
            return;
        }
        addAnomaly("fullscreen-parent-changed:" + survivor.taskId,
                formatSample("stage-end", snapshot)
                        + " lone fullscreen task=" + survivor.taskId
                        + " left fullscreen area="
                        + mSharedFullscreenParentFeatureId
                        + " for area=" + survivor.displayAreaFeatureId);
    }

    private void validateFullscreenTaskArea(
            final String reason,
            final Snapshot snapshot) {
        if (mSharedFullscreenParentFeatureId
                == DISPLAY_AREA_FEATURE_UNKNOWN) {
            return;
        }
        final TaskState host = snapshot.find(mHostTaskId);
        final boolean sessionParent = host != null
                && host.displayAreaFeatureId
                        == mSharedFullscreenParentFeatureId
                && hasSessionBackstop(
                        snapshot, mSharedFullscreenParentFeatureId);
        if (!sessionParent && host != null && host.displayAreaFeatureId
                == mSharedFullscreenParentFeatureId) {
            addAnomaly("desktop-host-in-fullscreen-area:" + mStage.name,
                    formatSample(reason, snapshot)
                            + " desktop host entered fullscreen area="
                            + mSharedFullscreenParentFeatureId);
        }
        int backstopCount = 0;
        for (final TaskState task : snapshot.tasks) {
            if (task.backstop
                    && task.displayId == mDisplayId
                    && task.displayAreaFeatureId
                            == mSharedFullscreenParentFeatureId) {
                backstopCount++;
            }
        }
        final int expectedBackstopCount = sessionParent ? 1 : 0;
        if (backstopCount != expectedBackstopCount) {
            addAnomaly("fullscreen-backstop:" + mStage.name + ':'
                            + backstopCount,
                    formatSample(reason, snapshot)
                            + " expected " + expectedBackstopCount
                            + " HOME shared-parent backstops,"
                            + " found=" + backstopCount);
        }
    }

    private boolean isDesktopFullscreenFixture(final TaskState task) {
        return task.fixture
                && task.displayId == mDisplayId
                && task.windowingMode == WINDOWING_MODE_FULLSCREEN;
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
            if (becameHiddenBeforeRemoval(
                    firstState, lastState, currentState)
                    && !hasVisibleFullscreenFixture(sample.snapshot)) {
                addAnomaly(
                        "task-hidden-before-removal:"
                                + stage.name + ':' + taskId,
                        formatSample(sample.reason, sample.snapshot)
                                + " task=" + taskId
                                + " became hidden before removal");
                return;
            }
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

    private static boolean becameHiddenBeforeRemoval(
            final TaskState first,
            final TaskState last,
            final TaskState current) {
        return first != null
                && last == null
                && current != null
                && first.visibilityKnown
                && first.visible
                && current.visibilityKnown
                && !current.visible;
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
        // Fullscreen peers can occupy both endpoints while the active task is
        // intentionally restored to another mode between them.
        final boolean stableFullscreen = hasVisibleFullscreenFixture(first)
                && hasVisibleFullscreenFixture(last)
                && !hasEndpointFixtureModeTransition(first, last);
        for (final Sample sample : stage.samples) {
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

    private boolean hasEndpointFixtureModeTransition(
            final Snapshot first,
            final Snapshot last) {
        for (final TaskState firstState : first.tasks) {
            if (!firstState.fixture) {
                continue;
            }
            final TaskState lastState = last.find(firstState.taskId);
            if (lastState != null
                    && lastState.fixture
                    && (firstState.displayId != lastState.displayId
                            || firstState.windowingMode
                                    != lastState.windowingMode)) {
                return true;
            }
        }
        return false;
    }

    private boolean allVisibilityKnown(final Stage stage) {
        for (final Sample sample : stage.samples) {
            if (!sample.snapshot.visibilityKnown) {
                return false;
            }
        }
        return true;
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
                && ((current.displayId == first.displayId
                            && current.windowingMode == last.windowingMode)
                        || (current.displayId == last.displayId
                            && current.windowingMode
                                    == first.windowingMode));
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

        boolean sameState(final Snapshot other) {
            if (other == null
                    || visibilityKnown != other.visibilityKnown
                    || tasks.size() != other.tasks.size()) {
                return false;
            }
            for (final TaskState task : tasks) {
                final TaskState candidate = other.find(task.taskId);
                if (candidate == null || !task.sameState(candidate)) {
                    return false;
                }
            }
            return true;
        }

        String summary(final int hostTaskId) {
            final StringBuilder output = new StringBuilder();
            for (final TaskState task : tasks) {
                if (task.taskId != hostTaskId && !task.fixture && !task.home
                        && !task.backstop) {
                    continue;
                }
                if (output.length() > 0) {
                    output.append(',');
                }
                if (task.taskId == hostTaskId) {
                    output.append("host");
                } else if (task.backstop) {
                    output.append("backstop");
                } else {
                    output.append(task.taskId);
                }
                output.append('=')
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
        final int displayAreaFeatureId;
        final boolean backstop;

        TaskState(
                final int taskId,
                final int displayId,
                final int windowingMode,
                final boolean visible,
                final boolean visibilityKnown,
                final boolean fixture,
                final boolean home,
                final int displayAreaFeatureId) {
            this(taskId, displayId, windowingMode, visible, visibilityKnown,
                    fixture, home, displayAreaFeatureId, false);
        }

        TaskState(
                final int taskId,
                final int displayId,
                final int windowingMode,
                final boolean visible,
                final boolean visibilityKnown,
                final boolean fixture,
                final boolean home,
                final int displayAreaFeatureId,
                final boolean backstop) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.windowingMode = windowingMode;
            this.visible = visible;
            this.visibilityKnown = visibilityKnown;
            this.fixture = fixture;
            this.home = home;
            this.displayAreaFeatureId = displayAreaFeatureId;
            this.backstop = backstop;
        }

        String stateKey() {
            return "display" + displayId + "/mode" + windowingMode;
        }

        boolean sameState(final TaskState other) {
            return other != null
                    && taskId == other.taskId
                    && displayId == other.displayId
                    && windowingMode == other.windowingMode
                    && visible == other.visible
                    && visibilityKnown == other.visibilityKnown
                    && fixture == other.fixture
                    && home == other.home
                    && displayAreaFeatureId == other.displayAreaFeatureId
                    && backstop == other.backstop;
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
